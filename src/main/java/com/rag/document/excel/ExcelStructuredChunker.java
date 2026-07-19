package com.rag.document.excel;

import com.rag.config.ExcelIngestionProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a spreadsheet into a list of {@link TextSegment}s that preserve its structure —
 * Workbook -&gt; Sheet -&gt; Row -&gt; Cell — instead of flattening the whole sheet into one blob of
 * pipe-separated text.
 * <p>
 * Each chunk corresponds to one (or a small configurable group of) spreadsheet row(s) and carries
 * two things:
 * <ol>
 *   <li>Human-readable text rendered as "{@code Header: Value}" pairs, prefixed with the
 *       workbook/sheet/row it came from — so both the embedding model and the keyword (BM25)
 *       index can match on column names and cell values directly.</li>
 *   <li>Structured metadata: {@code source}, {@code type}, {@code category}, {@code workbook},
 *       {@code sheet}, {@code sheetType} (a canonical tab-name-derived category — see
 *       {@link ExcelSheetType} — e.g. "offering", "optionalOffering", "refill",
 *       "relationsOverride", "characteristicsOverride", "priceListItem", "complexFlatRule",
 *       "discount", "rule"), {@code rowStart}, {@code rowEnd}, plus — whenever the sheet's header
 *       row matches one of the recognized synonyms (see {@link ExcelCatalogFields}) — canonical
 *       catalog-identifier fields: {@code offeringName}, {@code externalId},
 *       {@code flatOfferingId}, {@code changeRequestId}, {@code bundleId}, {@code bundleName},
 *       {@code tariffName}, {@code discountId}, {@code discountName}, {@code ruleId},
 *       {@code ruleName}, {@code relationId}, and {@code tabHeader} (the sheet's full header-row
 *       summary, for debugging), plus any column configured via {@code rag.excel.business-fields}
 *       (e.g. {@code tuti}) or generically auto-detected as an *Id/*Name/*Key/*Code column not
 *       already covered by either mechanism (see {@link ExcelCatalogFields#matchConfiguredFields}
 *       and {@link ExcelCatalogFields#matchDynamicHeaders}). This lets retrieval and citations
 *       answer questions like "which sheet contains Product Mapping?", "what's the Offering Id
 *       for CR-1029?", or "where is discount D-4521 defined?" precisely — filtering/matching on
 *       the actual business identifier, not just a filename.</li>
 * </ol>
 * Two additional passes run over the per-row field values once they're extracted (both via
 * {@link ExcelCatalogFields}, shared with the metadata backfill job so already-ingested chunks can
 * be brought in line with what a fresh ingest now produces):
 * <ul>
 *   <li>{@link ExcelCatalogFields#reconcileLegacyKeys} — renames any field stamped under a
 *       now-superseded dynamic key (e.g. {@code priceKeyId}) onto its current canonical key
 *       ({@code priceKey}), a no-op for a fresh ingest but kept for defense-in-depth.</li>
 *   <li>{@link ExcelCatalogFields#applyRangeWarnings} — flags any identifier whose value falls
 *       outside its sheet's documented reserved-ID range (see the "X range: NNNNNNNN[-MMMMMMMM]"
 *       header notes parsed by {@link ExcelCatalogFields#parseIdRangeHints}), stamping a
 *       {@code <field>RangeWarning} plus a combined {@code rangeWarnings}/{@code hasRangeWarning}
 *       pair onto the chunk.</li>
 * </ul>
 * Row numbers in both the text and the metadata are 1-based to match what a user sees in Excel.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelStructuredChunker {

    private final ExcelIngestionProperties properties;

    /**
     * @param is       the workbook's raw bytes
     * @param filename original filename (used to pick XLS vs XLSX and as the "source"/"workbook" label)
     */
    public List<TextSegment> chunk(InputStream is, String filename) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase();
        String workbookLabel = baseName(filename);

        try (Workbook workbook = lower.endsWith(".xls") ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {
            List<TextSegment> segments = new ArrayList<>();
            for (Sheet sheet : workbook) {
                segments.addAll(chunkSheet(sheet, filename, workbookLabel));
            }
            log.info("Structured Excel chunking of '{}' produced {} row-level chunk(s) across {} sheet(s)",
                    filename, segments.size(), workbook.getNumberOfSheets());
            return segments;
        }
    }

    // ── Per-sheet processing ─────────────────────────────────────────────────
    private List<TextSegment> chunkSheet(Sheet sheet, String source, String workbookLabel) {
        List<TextSegment> segments = new ArrayList<>();
        String sheetName = sheet.getSheetName();

        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < firstRowNum) {
            return segments; // empty sheet
        }

        // 1. Header detection: first non-blank row becomes the column-name row.
        Row headerRow = null;
        int firstDataRowNum = firstRowNum;
        if (properties.isHeaderDetection()) {
            for (int r = firstRowNum; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row != null && !isRowBlank(row)) {
                    headerRow = row;
                    firstDataRowNum = r + 1;
                    break;
                }
            }
        }
        List<String> headers = buildHeaders(headerRow);
        String headerSummary = String.join(", ", headers);
        String sheetType = ExcelSheetType.classify(sheetName);
        if (ExcelSheetType.GENERAL.equals(sheetType)) {
            log.info("Sheet '{}' in '{}' did not match a known ExcelSheetType pattern — ingesting as " +
                            "'general'. Headers: [{}]. If this tab represents a recurring business-domain " +
                            "type, share its name/headers so a dedicated pattern can be added to ExcelSheetType.",
                    sheetName, workbookLabel, headerSummary);
        }
        Map<String, Integer> catalogFieldColumns = ExcelCatalogFields.matchHeaders(headers);
        if (!catalogFieldColumns.isEmpty()) {
            log.debug("Sheet '{}': matched known catalog fields {} to header columns", sheetName, catalogFieldColumns.keySet());
        }
        // Configured business fields (rag.excel.business-fields) — for columns not already claimed
        // by a known alias above, e.g. "TUTI" -> tuti. Lets ops add support for a new business
        // field via application.yml alone, with no code change.
        Map<String, Integer> configuredFieldColumns = ExcelCatalogFields.matchConfiguredFields(
                headers, properties.getBusinessFields(), new HashSet<>(catalogFieldColumns.values()));
        if (!configuredFieldColumns.isEmpty()) {
            log.debug("Sheet '{}': matched configured business fields {} to header columns", sheetName, configuredFieldColumns.keySet());
        }
        // Generic fallback: any remaining *Id/*Name/*Key/*Code-looking column (not already claimed
        // above) gets captured automatically under a key derived from its own header text — this is
        // what lets a brand-new sheet's identifier columns get picked up without a code change.
        Set<Integer> claimedSoFar = new HashSet<>(catalogFieldColumns.values());
        claimedSoFar.addAll(configuredFieldColumns.values());
        Map<String, Integer> dynamicFieldColumns = ExcelCatalogFields.matchDynamicHeaders(headers, claimedSoFar);
        if (!dynamicFieldColumns.isEmpty()) {
            log.debug("Sheet '{}': auto-detected identifier-like columns {} (no alias needed)", sheetName, dynamicFieldColumns.keySet());
        }
        Map<String, Integer> allFieldColumns = new LinkedHashMap<>(catalogFieldColumns);
        allFieldColumns.putAll(configuredFieldColumns);
        allFieldColumns.putAll(dynamicFieldColumns);

        // Reserved-ID-block notes ("Rule ID range: 71320000") some sheets carry in a header cell —
        // parsed once per sheet and stamped onto every chunk from this sheet as documentation/
        // debugging context (see ExcelCatalogFields.parseIdRangeHints for details).
        Map<String, String> idRangeHints = ExcelCatalogFields.parseIdRangeHints(headers);
        String idRangeNote = ExcelCatalogFields.extractRawIdRangeNote(headers);
        if (!idRangeHints.isEmpty()) {
            log.debug("Sheet '{}': found reserved ID-range note(s): {}", sheetName, idRangeNote);
        }

        // 2. Collect data rows (skipping blank ones if configured), then group into chunks.
        List<Row> dataRows = new ArrayList<>();
        for (int r = firstDataRowNum; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null || (properties.isSkipBlankRows() && isRowBlank(row))) {
                continue;
            }
            dataRows.add(row);
        }

        int groupSize = Math.max(properties.getRowsPerChunk(), 1);
        for (int i = 0; i < dataRows.size(); i += groupSize) {
            List<Row> group = dataRows.subList(i, Math.min(i + groupSize, dataRows.size()));
            segments.add(buildChunk(source, workbookLabel, sheetName, headers, headerSummary, group,
                    allFieldColumns, idRangeHints, idRangeNote));
        }
        return segments;
    }

    private TextSegment buildChunk(String source, String workbookLabel, String sheetName,
                                    List<String> headers, String headerSummary, List<Row> group,
                                    Map<String, Integer> fieldColumns,
                                    Map<String, String> idRangeHints, String idRangeNote) {
        int rowStart = group.get(0).getRowNum() + 1;   // 1-based, matches Excel's UI
        int rowEnd = group.get(group.size() - 1).getRowNum() + 1;

        StringBuilder text = new StringBuilder();
        text.append("Workbook: ").append(workbookLabel).append('\n');
        text.append("Sheet: ").append(sheetName).append('\n');
        text.append(rowStart == rowEnd ? "Row: " + rowStart : "Rows: " + rowStart + "-" + rowEnd).append('\n');

        for (Row row : group) {
            if (group.size() > 1) {
                text.append("Row ").append(row.getRowNum() + 1).append(":\n");
            }
            Map<String, String> values = rowToColumnMap(row, headers);
            values.forEach((column, value) -> {
                if (!value.isEmpty()) {
                    text.append("  ").append(column).append(": ").append(value).append('\n');
                }
            });
        }

        Metadata metadata = Metadata.from("source", source)
                .put("type", "EXCEL")
                .put("category", com.rag.document.DocumentCategoryClassifier.CATALOG)
                .put("workbook", workbookLabel)
                .put("sheet", sheetName)
                .put("sheetType", ExcelSheetType.classify(sheetName))
                .put("rowStart", String.valueOf(rowStart))
                .put("rowEnd", String.valueOf(rowEnd));

        // Structured catalog-identifier fields — both known aliases (chunkSheet() via
        // ExcelCatalogFields.matchHeaders()) and generically auto-detected *Id/*Name/*Key/*Code
        // columns (via ExcelCatalogFields.matchDynamicHeaders()) — extracted here per row-group.
        // If a group spans multiple rows, the first row with a non-blank value for a given field wins.
        Map<String, String> extractedValues = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> fieldColumn : fieldColumns.entrySet()) {
            String field = fieldColumn.getKey();
            int columnIndex = fieldColumn.getValue();
            for (Row row : group) {
                Cell cell = row.getCell(columnIndex);
                String value = cell == null ? "" : getCellValue(cell).trim();
                if (!value.isEmpty()) {
                    metadata.put(field, value);
                    extractedValues.put(field, value);
                    break;
                }
            }
        }

        // Reconcile any field stamped under a now-superseded dynamic key (e.g. a "Price Key Id"
        // column derived as priceKeyId before priceKey's alias covered that spelling) onto its
        // canonical key. Normally a no-op at fresh-ingestion time since matchHeaders() already
        // claims the alias first — kept here so this code path stays guaranteed-consistent with
        // the metadata backfill job, which relies on the exact same call.
        if (ExcelCatalogFields.reconcileLegacyKeys(extractedValues)) {
            extractedValues.forEach(metadata::put);
        }

        // Header-row summary for this sheet — kept on every chunk (not just the header row itself)
        // so a retrieved chunk is self-describing for debugging: which columns it came from, without
        // a second lookup back to the source workbook.
        if (!headerSummary.isBlank()) {
            metadata.put(ExcelCatalogFields.TAB_HEADER, headerSummary);
        }

        // Reserved-ID-block context, if this sheet documented one — e.g. ruleIdRangeStart=71320000 —
        // plus the raw note text verbatim, for spotting a misassigned/miscategorized ID at a glance.
        idRangeHints.forEach(metadata::put);
        if (idRangeNote != null && !idRangeNote.isBlank()) {
            metadata.put(ExcelCatalogFields.ID_RANGE_NOTE, idRangeNote);
        }

        // Range-validation warnings: flag any extracted identifier whose value falls outside this
        // sheet's own documented reserved-ID range. Runs against a combined view of this row's
        // field values + the sheet's range hints (both needed by ExcelCatalogFields.checkRange);
        // only the newly-derived warning entries are copied back onto the chunk's metadata.
        if (properties.isRangeValidationEnabled()) {
            Map<String, String> rangeCheckView = new LinkedHashMap<>(extractedValues);
            rangeCheckView.putAll(idRangeHints);
            if (ExcelCatalogFields.applyRangeWarnings(rangeCheckView)) {
                rangeCheckView.forEach((key, value) -> {
                    if (key.endsWith(ExcelCatalogFields.RANGE_WARNING_SUFFIX)
                            || key.equals(ExcelCatalogFields.RANGE_WARNINGS_KEY)
                            || key.equals(ExcelCatalogFields.HAS_RANGE_WARNING)) {
                        metadata.put(key, value);
                    }
                });
                log.warn("Sheet '{}' rows {}-{}: {}", sheetName, rowStart, rowEnd,
                        rangeCheckView.get(ExcelCatalogFields.RANGE_WARNINGS_KEY));
            }
        }

        return TextSegment.from(text.toString().trim(), metadata);
    }

    // ── Row / header helpers ─────────────────────────────────────────────────
    private List<String> buildHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        if (headerRow == null) {
            return headers; // fall back to column letters at read time
        }
        int lastCell = headerRow.getLastCellNum();
        for (int c = 0; c < lastCell; c++) {
            Cell cell = headerRow.getCell(c);
            String value = cell == null ? "" : getCellValue(cell).trim();
            headers.add(value.isEmpty() ? columnLetter(c) : value);
        }
        return headers;
    }

    private Map<String, String> rowToColumnMap(Row row, List<String> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        int lastCell = row.getLastCellNum();
        for (int c = 0; c < lastCell; c++) {
            Cell cell = row.getCell(c);
            String value = cell == null ? "" : getCellValue(cell);
            if (value.length() > properties.getMaxCellLength()) {
                value = value.substring(0, properties.getMaxCellLength()) + "...";
            }
            String column = (headers != null && c < headers.size()) ? headers.get(c) : columnLetter(c);
            map.put(column, value);
        }
        return map;
    }

    private boolean isRowBlank(Row row) {
        for (Cell cell : row) {
            if (!getCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String getCellValue(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? String.valueOf(cell.getDateCellValue())
                        : String.valueOf(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    /** 0 -> A, 1 -> B, ... 26 -> AA, following spreadsheet column-letter convention. */
    private String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private String baseName(String filename) {
        if (filename == null) return "unknown";
        String normalized = filename.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }
}

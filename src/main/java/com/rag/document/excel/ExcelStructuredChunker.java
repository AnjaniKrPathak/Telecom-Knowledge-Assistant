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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>Structured metadata ({@code workbook}, {@code sheet}, {@code rowStart}, {@code rowEnd},
 *       {@code headers}) so retrieval and citations can answer questions like "which sheet
 *       contains Product Mapping?" or "where is error code XYZ defined?" precisely, instead of
 *       pointing only at a filename.</li>
 * </ol>
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
            segments.add(buildChunk(source, workbookLabel, sheetName, headers, headerSummary, group));
        }
        return segments;
    }

    private TextSegment buildChunk(String source, String workbookLabel, String sheetName,
                                    List<String> headers, String headerSummary, List<Row> group) {
        int rowStart = group.get(0).getRowNum() + 1;   // 1-based, matches Excel's UI
        int rowEnd = group.get(group.size() - 1).getRowNum() + 1;

        StringBuilder text = new StringBuilder();
        text.append("Workbook: ").append(workbookLabel).append('\n');
        text.append("Sheet: ").append(sheetName).append('\n');
        text.append(rowStart == rowEnd ? "Row: " + rowStart : "Rows: " + rowStart + "-" + rowEnd).append('\n');

        List<Map<String, String>> rowValuesList = new ArrayList<>(group.size());
        for (Row row : group) {
            if (group.size() > 1) {
                text.append("Row ").append(row.getRowNum() + 1).append(":\n");
            }
            Map<String, String> values = rowToColumnMap(row, headers);
            rowValuesList.add(values);
            values.forEach((column, value) -> {
                if (!value.isEmpty()) {
                    text.append("  ").append(column).append(": ").append(value).append('\n');
                }
            });
        }

        Metadata metadata = Metadata.from("source", source)
                .put("type", "EXCEL")
                .put("workbook", workbookLabel)
                .put("sheet", sheetName)
                .put("rowStart", String.valueOf(rowStart))
                .put("rowEnd", String.valueOf(rowEnd))
                .put("chunkKind", group.size() > 1 ? "EXCEL_ROWS" : "EXCEL_ROW");
        if (!headerSummary.isBlank()) {
            metadata.put("headers", headerSummary);
        }

        // Surface configured business columns (rag.excel.business-fields) directly as metadata —
        // e.g. "Offering Name" -> offeringName, "Flat Offering ID" -> flatOfferingId — so
        // retrieval can filter/debug on them precisely instead of relying only on
        // full-text/vector matching against the rendered row text.
        extractBusinessFields(rowValuesList).forEach(metadata::put);

        return TextSegment.from(text.toString().trim(), metadata);
    }

    /**
     * Looks up each configured business column (case-insensitive, whitespace-tolerant header
     * match) across every row in this chunk's group and returns the first non-blank value found
     * for each, keyed by its configured metadata name (rag.excel.business-fields).
     */
    private Map<String, String> extractBusinessFields(List<Map<String, String>> rowValuesList) {
        Map<String, String> businessFields = properties.getBusinessFields();
        if (businessFields == null || businessFields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : businessFields.entrySet()) {
            String headerName = field.getKey();
            String metadataKey = field.getValue();
            if (metadataKey == null || metadataKey.isBlank()) {
                continue;
            }
            for (Map<String, String> rowValues : rowValuesList) {
                String value = findValueIgnoreCase(rowValues, headerName);
                if (value != null && !value.isBlank()) {
                    result.put(metadataKey, value);
                    break;
                }
            }
        }
        return result;
    }

    private String findValueIgnoreCase(Map<String, String> rowValues, String headerName) {
        if (headerName == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : rowValues.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(headerName.trim())) {
                return entry.getValue();
            }
        }
        return null;
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

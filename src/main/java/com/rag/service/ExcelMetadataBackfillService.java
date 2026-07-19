package com.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.FullTextSearchSchemaInitializer;
import com.rag.document.excel.ExcelCatalogFields;
import com.rag.model.dto.MetadataBackfillReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-tags Excel chunks that were embedded <em>before</em> the {@code priceKey}/{@code priceKeyId}
 * alias reconciliation and range-validation-warning features existed (see
 * {@link ExcelCatalogFields#reconcileLegacyKeys} and {@link ExcelCatalogFields#applyRangeWarnings}),
 * so historical rows end up carrying the same metadata a fresh ingest would produce today.
 * <p>
 * Deliberately does <b>not</b> re-parse the source workbook or re-embed anything: every input
 * these two passes need (the row's own field values, plus the sheet's {@code *RangeStart}/
 * {@code *RangeEnd} hints) is already sitting in each chunk's stored metadata JSON, so this job
 * just reads that JSON back out of the pgvector table, runs the same two passes
 * {@link com.rag.document.excel.ExcelStructuredChunker} runs at ingestion time, and writes the
 * JSON back in place when anything changed. Cheap, idempotent, and safe to re-run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelMetadataBackfillService {

    private final JdbcTemplate jdbcTemplate;
    private final FullTextSearchSchemaInitializer schemaInitializer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_ERROR_SAMPLE = 20;

    @Value("${pgvector.table-name}")
    private String tableName;

    /** Runs the backfill over every stored Excel chunk. See class javadoc for what it does and why
     *  no re-embedding is required. */
    public MetadataBackfillReport runBackfill() {
        LocalDateTime startedAt = LocalDateTime.now();

        if (!isSafeIdentifier(tableName)) {
            throw new IllegalStateException("Refusing to run metadata backfill: unsafe table name '" + tableName + "'");
        }
        String idColumn = resolveIdColumn();
        if (!isSafeIdentifier(idColumn)) {
            throw new IllegalStateException("Refusing to run metadata backfill: unsafe id column name '" + idColumn + "'");
        }
        String metadataColumnType = resolveMetadataColumnType();

        int scanned = 0, updated = 0, legacyReconciled = 0, rangeWarningsAdded = 0;
        List<String> errorSample = new ArrayList<>();

        String selectSql = String.format(
                "SELECT %s AS chunk_id, metadata FROM %s WHERE metadata->>'type' = 'EXCEL'",
                idColumn, tableName);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql);
        for (Map<String, Object> row : rows) {
            scanned++;
            Object chunkId = row.get("chunk_id");
            String rawMetadata = asJsonString(row.get("metadata"));
            try {
                Map<String, String> metadata = parseAsStringMap(rawMetadata);
                if (metadata.isEmpty()) {
                    continue;
                }

                boolean hadWarningsAlready = "true".equals(metadata.get(ExcelCatalogFields.HAS_RANGE_WARNING));
                boolean reconciled = ExcelCatalogFields.reconcileLegacyKeys(metadata);
                boolean rangeChanged = ExcelCatalogFields.applyRangeWarnings(metadata);

                if (!reconciled && !rangeChanged) {
                    continue;
                }

                updateMetadata(idColumn, metadataColumnType, chunkId, metadata);
                updated++;
                if (reconciled) {
                    legacyReconciled++;
                }
                if (rangeChanged && !hadWarningsAlready && "true".equals(metadata.get(ExcelCatalogFields.HAS_RANGE_WARNING))) {
                    rangeWarningsAdded++;
                }
            } catch (Exception e) {
                log.warn("Metadata backfill: could not process chunk {}: {}", chunkId, e.getMessage());
                if (errorSample.size() < MAX_ERROR_SAMPLE) {
                    errorSample.add(chunkId + ": " + e.getMessage());
                }
            }
        }

        LocalDateTime completedAt = LocalDateTime.now();
        int errorCount = errorSample.size();
        log.info("Excel metadata backfill complete: {} scanned, {} updated ({} legacy-key rows, " +
                        "{} new range warnings), {} errors, {} -> {}",
                scanned, updated, legacyReconciled, rangeWarningsAdded, errorCount, startedAt, completedAt);

        return MetadataBackfillReport.builder()
                .rowsScanned(scanned)
                .rowsUpdated(updated)
                .legacyKeysReconciled(legacyReconciled)
                .rangeWarningsAdded(rangeWarningsAdded)
                .errorCount(errorCount)
                .errorSample(errorSample)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }

    // ── Metadata (de)serialization ────────────────────────────────────────────
    // Every value the app ever stamps onto a chunk's metadata is a String (see
    // ExcelStructuredChunker / Metadata.put(String, String) throughout), so round-tripping through
    // a Map<String,String> is safe and keeps ExcelCatalogFields' pass logic identical to the one
    // that runs at fresh-ingestion time (which also operates on Map<String,String>).

    /** A jsonb/json column comes back from the PostgreSQL JDBC driver as a {@link PGobject}, not a
     *  plain String (a text/varchar column would come back as a String directly) — this reads
     *  either shape so the raw-cast type doesn't need to be assumed in advance. */
    private String asJsonString(Object metadataValue) {
        if (metadataValue == null) {
            return null;
        }
        if (metadataValue instanceof PGobject pgObject) {
            return pgObject.getValue();
        }
        return metadataValue.toString();
    }

    private Map<String, String> parseAsStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("unparseable metadata JSON: " + e.getMessage(), e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    private void updateMetadata(String idColumn, String metadataColumnType, Object chunkId,
                                 Map<String, String> metadata) {
        String json;
        try {
            json = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize updated metadata: " + e.getMessage(), e);
        }
        String cast = ("jsonb".equalsIgnoreCase(metadataColumnType) || "json".equalsIgnoreCase(metadataColumnType))
                ? "::" + metadataColumnType.toLowerCase() : "";
        String updateSql = String.format("UPDATE %s SET metadata = ?%s WHERE %s = ?", tableName, cast, idColumn);
        jdbcTemplate.update(updateSql, json, chunkId);
    }

    /**
     * Lists every distinct (workbook, sheet) pair among already-ingested Excel chunks whose
     * {@code sheetType} classified as {@link com.rag.document.excel.ExcelSheetType#GENERAL} — i.e.
     * tabs that didn't match any of the known business-domain patterns in
     * {@link com.rag.document.excel.ExcelSheetType}. This is the actionable inventory of "sheets
     * whose headers/domain type haven't been shared yet": for each one, share its tab name and
     * header row so a dedicated {@code ExcelSheetType} pattern (and, if useful, {@code SYNONYMS}
     * aliases in {@link ExcelCatalogFields}) can be added for it.
     */
    public List<Map<String, Object>> listUnclassifiedSheets() {
        if (!isSafeIdentifier(tableName)) {
            throw new IllegalStateException("Refusing to query: unsafe table name '" + tableName + "'");
        }
        String sql = String.format("""
                SELECT metadata->>'workbook' AS workbook,
                       metadata->>'sheet' AS sheet,
                       metadata->>'tabHeader' AS headers,
                       count(*) AS chunk_count
                FROM %s
                WHERE metadata->>'type' = 'EXCEL' AND metadata->>'sheetType' = 'general'
                GROUP BY 1, 2, 3
                ORDER BY workbook, sheet
                """, tableName);
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Lists already-ingested Excel chunks currently flagged with a range-validation warning
     * ({@link ExcelCatalogFields#HAS_RANGE_WARNING} = "true"), most useful right after a backfill
     * run to see exactly what got flagged.
     */
    public List<Map<String, Object>> listRangeWarnings(int limit) {
        if (!isSafeIdentifier(tableName)) {
            throw new IllegalStateException("Refusing to query: unsafe table name '" + tableName + "'");
        }
        String sql = String.format("""
                SELECT metadata->>'workbook' AS workbook,
                       metadata->>'sheet' AS sheet,
                       metadata->>'rowStart' AS row_start,
                       metadata->>'rangeWarnings' AS warnings
                FROM %s
                WHERE metadata->>'type' = 'EXCEL' AND metadata->>'hasRangeWarning' = 'true'
                ORDER BY workbook, sheet, (metadata->>'rowStart')::int
                LIMIT ?
                """, tableName);
        return jdbcTemplate.queryForList(sql, Math.max(limit, 1));
    }

    // ── Table introspection (mirrors FullTextSearchSchemaInitializer's approach) ───────────────
    private String resolveIdColumn() {
        String cached = schemaInitializer.getIdColumnName();
        if (cached != null) {
            return cached;
        }
        List<String> pkColumns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class, tableName);
        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("could not resolve a primary key column for table " + tableName);
        }
        return pkColumns.get(0);
    }

    private String resolveMetadataColumnType() {
        List<String> types = jdbcTemplate.queryForList("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = ? AND column_name = 'metadata'
                """, String.class, tableName);
        return types.isEmpty() ? "json" : types.get(0);
    }

    private boolean isSafeIdentifier(String identifier) {
        return identifier != null && identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }
}

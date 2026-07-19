package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the "rag.excel.*" properties from application.yml.
 * <p>
 * Controls how spreadsheets are turned into chunks during ingestion. Unlike PDF/DOCX/TXT
 * (which are split by the generic recursive character splitter), Excel workbooks are chunked
 * along their natural structure — Workbook -&gt; Sheet -&gt; Row -&gt; Cell — so that retrieval can
 * answer structural questions ("which sheet has X", "where is error code Y defined") instead
 * of matching against an undifferentiated blob of pipe-separated text.
 *
 * <pre>
 * rag:
 *   excel:
 *     rows-per-chunk: 1        # how many data rows go into a single embedded chunk
 *     header-detection: true   # use the first non-blank row of each sheet as column headers
 *     max-cell-length: 2000    # truncate any single cell's text beyond this many characters
 *     skip-blank-rows: true    # don't create chunks for rows where every cell is empty
 *     business-fields:         # spreadsheet column header -> metadata key, surfaced on every
 *       "Offering Name": offeringName      # chunk built from a row that has that column, so
 *       "Flat Offering ID": flatOfferingId # retrieval/debugging can key off these directly
 *       "Offering ID": offeringId          # instead of only full-text/vector matching.
 *       "External ID": externalId
 *       "TUTI": tuti
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "rag.excel")
@Data
public class ExcelIngestionProperties {

    /**
     * How many consecutive data rows are grouped into a single chunk (and therefore a single
     * embedding). 1 gives the most precise row-level retrieval ("Row: 245") at the cost of more
     * embedding calls on very large sheets; increase this for huge sheets where a handful of
     * neighboring rows are still a coherent unit of retrieval.
     */
    private int rowsPerChunk = 1;

    /**
     * When true, the first non-blank row of each sheet is treated as a header row and its cell
     * values become column names (e.g. "Error Code", "Description") used to label every
     * subsequent row's cells. When false, columns are labelled by spreadsheet letter (A, B, C...).
     */
    private boolean headerDetection = true;

    /** Any single cell's rendered text is truncated to this many characters. */
    private int maxCellLength = 2000;

    /** Rows where every cell is blank are skipped instead of producing an empty chunk. */
    private boolean skipBlankRows = true;

    /**
     * When true (default), a row's identifier value is checked against its sheet's own documented
     * reserved-ID range (an "X range: NNNNNNNN[-MMMMMMMM]" note found in a header cell — see
     * {@link com.rag.document.excel.ExcelCatalogFields#parseIdRangeHints}) and flagged with a
     * {@code *RangeWarning} metadata entry when it falls outside that range. Set to false to skip
     * this check entirely (the range hints are still parsed and stamped either way).
     */
    private boolean rangeValidationEnabled = true;

    /**
     * Maps a spreadsheet column header (matched case-insensitively, whitespace-trimmed) to the
     * metadata key its value should be stored under — e.g. "Flat Offering ID" -&gt; "flatOfferingId".
     * Every configured column that's present (and non-blank) in a chunk's row(s) gets copied
     * straight into that chunk's {@link dev.langchain4j.data.document.Metadata}, so retrieval can
     * filter/debug on business identifiers precisely, instead of relying only on
     * full-text/vector matching. Populated with sensible telecom-offering defaults below —
     * override or add to this map in application.yml for any other column you rely on.
     */
    private Map<String, String> businessFields = new LinkedHashMap<>(Map.of(
            "Offering Name", "offeringName",
            "Flat Offering ID", "flatOfferingId",
            "Offering ID", "offeringId",
            "External ID", "externalId",
            "TUTI", "tuti"
    ));
}

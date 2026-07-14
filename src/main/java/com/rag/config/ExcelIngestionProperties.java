package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
}

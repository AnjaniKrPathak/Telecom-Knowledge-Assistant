package com.rag.search;

/**
 * A single retrieved chunk after hybrid (vector + keyword) retrieval and fusion.
 *
 * @param id             the embedding store row id (primary key of the vector table)
 * @param text           the chunk's raw text
 * @param source         value of the "source" metadata key (filename / URL)
 * @param type           value of the "type" metadata key (PDF, DOCX, EXCEL, TXT, URL...)
 * @param workbook       for EXCEL chunks: the workbook (spreadsheet file) name; null otherwise
 * @param sheet          for EXCEL chunks: the sheet name the chunk came from; null otherwise
 * @param rowStart       for EXCEL chunks: 1-based first spreadsheet row covered by this chunk; null otherwise
 * @param rowEnd         for EXCEL chunks: 1-based last spreadsheet row covered by this chunk; null otherwise
 * @param vectorScore    cosine similarity from the vector leg, or null if this chunk was
 *                       only found via keyword search
 * @param bm25Score      Okapi BM25 relevance score from the keyword leg, or null if this
 *                       chunk was only found via vector search
 * @param combinedScore  Reciprocal Rank Fusion score used for final ordering
 * @param matchType      "VECTOR", "KEYWORD", or "HYBRID" — which leg(s) surfaced this chunk
 */
public record RetrievedChunk(
        String id,
        String text,
        String source,
        String type,
        String workbook,
        String sheet,
        String rowStart,
        String rowEnd,
        Double vectorScore,
        Double bm25Score,
        double combinedScore,
        String matchType
) {

    /** Human-readable spreadsheet locator, e.g. "Product Mapping, Row 245" or "Product Mapping, Rows 10-14". */
    public String excelLocation() {
        if (sheet == null) {
            return null;
        }
        if (rowStart == null) {
            return sheet;
        }
        String rows = rowStart.equals(rowEnd) ? "Row " + rowStart : "Rows " + rowStart + "-" + rowEnd;
        return sheet + ", " + rows;
    }

    /** Mutable accumulator used while merging the two retrieval legs by RRF. */
    static final class Builder {
        private final String id;
        private String text;
        private String source;
        private String type;
        private String workbook;
        private String sheet;
        private String rowStart;
        private String rowEnd;
        private Double vectorScore;
        private Double bm25Score;
        private double combinedScore;
        private boolean fromVector;
        private boolean fromKeyword;

        Builder(String id) {
            this.id = id;
        }

        Builder withVector(String text, String source, String type,
                            String workbook, String sheet, String rowStart, String rowEnd,
                            double vectorScore, double rrfContribution) {
            this.text = text;
            this.source = source;
            this.type = type;
            this.workbook = workbook;
            this.sheet = sheet;
            this.rowStart = rowStart;
            this.rowEnd = rowEnd;
            this.vectorScore = vectorScore;
            this.combinedScore += rrfContribution;
            this.fromVector = true;
            return this;
        }

        Builder withKeyword(String text, String source, String type,
                             String workbook, String sheet, String rowStart, String rowEnd,
                             double bm25Score, double rrfContribution) {
            if (this.text == null) {
                this.text = text;
                this.source = source;
                this.type = type;
                this.workbook = workbook;
                this.sheet = sheet;
                this.rowStart = rowStart;
                this.rowEnd = rowEnd;
            }
            this.bm25Score = bm25Score;
            this.combinedScore += rrfContribution;
            this.fromKeyword = true;
            return this;
        }

        RetrievedChunk build() {
            String matchType = fromVector && fromKeyword ? "HYBRID" : fromVector ? "VECTOR" : "KEYWORD";
            return new RetrievedChunk(id, text, source, type, workbook, sheet, rowStart, rowEnd,
                    vectorScore, bm25Score, combinedScore, matchType);
        }
    }
}

package com.rag.query;

/**
 * The two retrieval intents a question can be routed to. See {@link IntentDetectionService}
 * for how a question is classified, and {@code RagQueryService} for how each intent maps to a
 * different retrieval-time metadata filter.
 */
public enum QueryIntent {

    /**
     * The question is asking for a specific record/attribute — an Offering Name, Flat Offering
     * ID, External ID, CR ID, Product ID, Service ID, etc. These live in the structured Excel
     * catalog, so retrieval is restricted to Excel-derived chunks and DOCX is skipped entirely.
     */
    LOOKUP,

    /**
     * The question is asking for a definition, explanation, or "how does X work" — this lives in
     * the narrative documents (FDS, BRD, business rules), so retrieval is restricted to
     * DOCX-derived (and other narrative-document) chunks and the Excel catalog is skipped.
     */
    EXPLANATION
}

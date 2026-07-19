package com.rag.document;

import java.util.Set;

/**
 * Assigns each ingested chunk a retrieval-time {@code category} metadata tag, which is what
 * {@code RagQueryService} filters on once a question's intent has been classified
 * (see {@code com.rag.query.IntentDetectionService}).
 * <p>
 * This gives the effect of separate retrieval "collections" — catalog / FDS / rules / BRD —
 * without standing up separate pgvector tables: everything still lives in one
 * {@code document_embeddings} table (so the existing hybrid/BM25 search over that single table
 * keeps working unmodified), but each row now carries a {@code category} entry in its metadata
 * that a {@code Filter} can restrict on at query time.
 */
public final class DocumentCategoryClassifier {

    private DocumentCategoryClassifier() {
    }

    /** Structured record lookups — Offering Name, Flat Offering ID, External/CR/Product/Service ID, etc. */
    public static final String CATALOG = "catalog";

    /** Functional Design Spec narrative content. */
    public static final String FDS = "fds";

    /** Business Requirements Document narrative content. */
    public static final String BRD = "brd";

    /** Business/validation rules narrative content. */
    public static final String RULES = "rules";

    /** Any other narrative/explanatory document that doesn't match a more specific bucket. */
    public static final String GENERAL = "general";

    /** Every category an EXPLANATION-intent query is allowed to retrieve from. */
    public static final Set<String> EXPLANATION_CATEGORIES = Set.of(FDS, BRD, RULES, GENERAL);

    /**
     * @param filename original filename or path (may be null)
     * @param type     the document's {@link DocumentType}
     * @return the category to tag this document's chunks with
     */
    public static String classify(String filename, DocumentType type) {
        if (type == DocumentType.EXCEL) {
            return CATALOG;
        }
        String lower = filename == null ? "" : filename.toLowerCase();
        if (containsWord(lower, "fds") || lower.contains("functional design") || lower.contains("functional_design")) {
            return FDS;
        }
        if (containsWord(lower, "brd") || lower.contains("business requirement")) {
            return BRD;
        }
        if (lower.contains("rule")) {
            return RULES;
        }
        return GENERAL;
    }

    /** Matches "fds"/"brd" as a standalone token (surrounded by non-letters or string edges) so it
     *  doesn't fire on unrelated filenames that merely contain those three letters as a substring. */
    private static boolean containsWord(String haystack, String word) {
        return haystack.matches(".*(^|[^a-z])" + word + "([^a-z]|$).*");
    }
}

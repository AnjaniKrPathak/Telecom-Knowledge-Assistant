package com.rag.document;

public enum DocumentType {
    PDF,
    DOCX,
    EXCEL,
    TXT,
    URL;

    public static DocumentType fromFilename(String filename) {
        if (filename == null) return TXT;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return PDF;
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return DOCX;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return EXCEL;
        if (lower.startsWith("http://") || lower.startsWith("https://")) return URL;
        return TXT;
    }

    /**
     * Whitelist used when scanning a folder for ingestible files, so that
     * unrelated files (e.g. .zip, .exe, hidden/system files) are skipped
     * instead of being force-read as plain text.
     */
    public static boolean isSupportedFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf")
                || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".xlsx") || lower.endsWith(".xls")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }
}

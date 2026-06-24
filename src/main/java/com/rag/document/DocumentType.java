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
}

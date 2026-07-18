package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Binds "rag.query-routing.*" — keyword-based pre-filtering that narrows retrieval to one
 * document type (metadata "type" = EXCEL or DOCX) before the vector search runs, based on what
 * the question looks like it's asking for. See com.rag.search.QueryTypeClassifier.
 *
 * <pre>
 * rag:
 *   query-routing:
 *     enabled: true
 *     excel-keywords: ["flat offering", "offering id", "offering name", "external id", "tuti"]
 *     docx-keywords: ["what is", "how does", "how do", "explain", "why does", "describe"]
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "rag.query-routing")
@Data
public class QueryRoutingProperties {

    /** Master switch. When false, every query searches across all document types (no filtering). */
    private boolean enabled = true;

    /** Case-insensitive substrings — if the question contains any of these, retrieval is filtered to metadata type=EXCEL. */
    private List<String> excelKeywords = List.of(
            "flat offering", "offering id", "offering name", "external id", "tuti"
    );

    /**
     * Case-insensitive substrings — checked only if no Excel keyword matched first. If the
     * question contains any of these, retrieval is filtered to metadata type=DOCX.
     */
    private List<String> docxKeywords = List.of(
            "what is", "what are", "how does", "how do", "how is", "explain", "why does", "why is", "describe"
    );
}

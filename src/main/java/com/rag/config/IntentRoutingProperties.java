package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds "rag.intent-routing.*" — controls whether lookup questions (Offering Name, Flat Offering
 * ID, External ID, CR ID, Product ID, Service ID, ...) are routed to the Excel-derived catalog
 * chunks only, and explanation questions ("What is X?", "Explain Y", "How does Z work?") are
 * routed to the DOCX-derived narrative chunks only. See com.rag.query.IntentDetectionService and
 * com.rag.document.DocumentCategoryClassifier.
 *
 * rag:
 *   intent-routing:
 *     enabled: true
 *     fallback-when-empty: true   # if the category-filtered search returns nothing, retry unfiltered
 *                                  # rather than than telling the user "I don't know" (covers documents
 *                                  # ingested before this feature existed and so have no category tag)
 */
@Configuration
@ConfigurationProperties(prefix = "rag.intent-routing")
@Data
public class IntentRoutingProperties {

    /** Master switch. When false, retrieval searches across all categories as before. */
    private boolean enabled = true;

    /** When true, an empty filtered result set triggers one unfiltered retry instead of returning "no answer". */
    private boolean fallbackWhenEmpty = true;
}

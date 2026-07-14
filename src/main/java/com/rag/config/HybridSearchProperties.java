package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.hybrid.*" properties from application.yml.
 *
 * rag:
 *   hybrid:
 *     enabled: true
 *     candidate-multiplier: 4   # fetch (topK * multiplier) candidates from each retriever before fusion
 *     rrf-k: 60                 # Reciprocal Rank Fusion smoothing constant (standard default = 60)
 *     min-vector-score: 0.5     # cosine-similarity floor applied to the vector leg only
 *     fts-language: english     # PostgreSQL text-search configuration used for to_tsvector / tsquery
 */
@Configuration
@ConfigurationProperties(prefix = "rag.hybrid")
@Data
public class HybridSearchProperties {

    /** Master switch. When false, RagQueryService falls back to pure vector search. */
    private boolean enabled = true;

    /**
     * How many candidates to pull from EACH retriever (vector + keyword) relative to the
     * final top-k the caller wants, e.g. topK=5 and multiplier=4 -> 20 candidates per leg
     * before fusion narrows it back down to 5. A wider candidate pool improves fusion quality.
     */
    private int candidateMultiplier = 4;

    /** Reciprocal Rank Fusion constant. Higher values flatten the influence of rank position. */
    private int rrfK = 60;

    /** Minimum cosine similarity score required for a chunk to qualify via the vector leg. */
    private double minVectorScore = 0.5;

    /** Postgres text-search configuration (language) used for both indexing and querying. */
    private String ftsLanguage = "english";

    /** BM25 term-frequency saturation constant. Higher values let repeated terms keep adding score for longer. */
    private double bm25K1 = 1.2;

    /** BM25 length-normalization constant (0 = ignore document length, 1 = fully normalize by it). */
    private double bm25B = 0.75;

    /** How often the cached corpus-wide BM25 statistics (N, avgdl) are refreshed, in milliseconds. */
    private long statsRefreshIntervalMs = 300_000;
}

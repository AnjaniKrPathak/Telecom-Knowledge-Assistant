package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds "rag.reranker.*" — selects and configures the pluggable reranking backend applied to
 * retrieved chunks before they're used to build the LLM prompt. See com.rag.rerank.RerankerProvider.
 *
 * rag:
 *   reranker:
 *     enabled: true
 *     provider: huggingface        # huggingface | llm | none
 *     candidate-pool-size: 20      # vector-search fetch size BEFORE reranking (>= rag.max-results)
 *     fail-open: true              # if the reranker call fails, fall back to vector-similarity order
 *     huggingface:
 *       base-url: http://localhost:8081   # local Hugging Face Text-Embeddings-Inference (TEI) server
 *       timeout-ms: 4000
 */
@Configuration
@ConfigurationProperties(prefix = "rag.reranker")
@Data
public class RerankerProperties {

    /** Master switch. When false, retrieved chunks keep their original vector-similarity order. */
    private boolean enabled = true;

    /** Which RerankerProvider bean (matched by id()) handles reranking: "huggingface", "llm", or "none". */
    private String provider = "huggingface";

    /** How many candidates to pull from pgvector BEFORE reranking — cast a wider net than rag.max-results so the reranker has real choices to make. */
    private int candidatePoolSize = 20;

    /** If the reranker call fails/times out, fall back to original vector order instead of failing the query. */
    private boolean failOpen = true;

    private final HuggingFace huggingface = new HuggingFace();

    @Data
    public static class HuggingFace {
        /** Base URL of a locally self-hosted Hugging Face Text-Embeddings-Inference (TEI) rerank server — no API key needed. */
        private String baseUrl = "http://localhost:8081";

        /** Request timeout in milliseconds — kept short so a stalled reranker degrades fast instead of hanging the query. */
        private int timeoutMs = 4000;
    }
}

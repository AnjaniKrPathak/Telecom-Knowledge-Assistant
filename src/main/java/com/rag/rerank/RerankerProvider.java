package com.rag.rerank;

import java.util.List;

/**
 * A pluggable relevance-scoring backend that re-orders retrieved chunks against the original
 * question, more accurately than raw vector-similarity alone.
 * <p>
 * To add a new backend: implement this interface, give it a unique {@link #id()}, and register
 * it as a Spring bean (e.g. {@code @Component}). Select it via {@code rag.reranker.provider} in
 * application.yml — no changes needed anywhere else; {@code RerankerService} auto-discovers every
 * bean of this type and picks the one whose {@code id()} matches the config.
 */
public interface RerankerProvider {

    /** Must match the value of {@code rag.reranker.provider} that selects this implementation. */
    String id();

    /**
     * Re-scores {@code candidates} against {@code query}. Implementations should return one
     * {@link RerankedCandidate} per input candidate (or as many as they can confidently score),
     * ordered best-first — {@code RerankerService} handles mapping back to the original objects
     * and trimming to the final context size, so providers don't need to worry about either.
     *
     * @param topN a hint for how many results the caller ultimately needs — providers that charge
     *             per item (e.g. a paid API) may use it to cap work; local/batch providers can ignore it.
     */
    List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topN);
}

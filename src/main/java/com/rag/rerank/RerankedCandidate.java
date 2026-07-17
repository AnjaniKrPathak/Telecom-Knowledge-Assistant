package com.rag.rerank;

/**
 * The reranked score for one candidate, correlated back to the original via {@code id}
 * (see {@link RerankCandidate#id()}).
 */
public record RerankedCandidate(String id, double score) {}

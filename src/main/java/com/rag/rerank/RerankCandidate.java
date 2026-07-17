package com.rag.rerank;

/**
 * One retrieved chunk offered up for reranking, stripped of any embedding-store-specific type
 * (deliberately not an EmbeddingMatch/TextSegment) so RerankerProvider implementations don't need
 * a LangChain4j dependency and can be reused against any retrieval source.
 *
 * @param id            correlates this candidate back to its original match — RerankerService
 *                      assigns this (the candidate's index in the original list) and uses it to
 *                      re-map RerankedCandidate results, so providers never need to know anything
 *                      about the caller's data model.
 * @param text          the chunk's text content to be scored against the query
 * @param originalScore the vector-similarity (cosine) score, kept for providers that want it as a
 *                      tie-breaker or that choose not to rerank (see NoOpRerankerProvider)
 */
public record RerankCandidate(String id, String text, double originalScore) {}

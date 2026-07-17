package com.rag.rerank;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Selected via rag.reranker.provider=none (or automatically when rag.reranker.enabled=false) — leaves vector-similarity order untouched. */
@Component
public class NoOpRerankerProvider implements RerankerProvider {

    @Override
    public String id() {
        return "none";
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topN) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RerankCandidate::originalScore).reversed())
                .map(c -> new RerankedCandidate(c.id(), c.originalScore()))
                .toList();
    }
}

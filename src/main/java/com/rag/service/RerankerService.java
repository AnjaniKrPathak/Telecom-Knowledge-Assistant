package com.rag.service;

import com.rag.config.RerankerProperties;
import com.rag.rerank.RerankCandidate;
import com.rag.rerank.RerankedCandidate;
import com.rag.rerank.RerankerProvider;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates reranking of retrieved chunks for RagQueryService: picks the active
 * {@link RerankerProvider} bean by {@code rag.reranker.provider}, translates to/from the
 * embedding-store-specific {@code EmbeddingMatch<TextSegment>} type so providers stay
 * framework-agnostic, and falls back to the original vector-similarity order if the provider
 * fails and {@code rag.reranker.fail-open} is true — a bad/unreachable reranker degrades
 * gracefully instead of breaking the query.
 * <p>
 * To add a new backend, implement {@link RerankerProvider} and register it as a Spring bean;
 * every implementation on the classpath is auto-discovered here via constructor injection of
 * {@code List<RerankerProvider>} — no changes needed in this class.
 */
@Slf4j
@Service
public class RerankerService {

    private final Map<String, RerankerProvider> providersById;
    private final RerankerProperties properties;

    public RerankerService(List<RerankerProvider> providers, RerankerProperties properties) {
        this.providersById = providers.stream()
                .collect(Collectors.toMap(RerankerProvider::id, p -> p));
        this.properties = properties;
        log.info("🔀 Reranker initialized (enabled={}, provider={}, candidatePoolSize={}, available providers={})",
                properties.isEnabled(), properties.getProvider(), properties.getCandidatePoolSize(),
                providersById.keySet());
    }

    /**
     * Re-orders {@code matches} using the configured provider. Returns the FULL list re-sorted
     * (never trimmed) so callers can still apply their own downstream adjustments (e.g.
     * feedback-based boosting in RagQueryService) before cutting down to the final context size.
     */
    public List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> matches) {
        if (!properties.isEnabled() || matches.size() <= 1) {
            return matches;
        }

        RerankerProvider provider = providersById.get(properties.getProvider());
        if (provider == null) {
            log.warn("Unknown rag.reranker.provider '{}' (available: {}) — skipping rerank, keeping vector order",
                    properties.getProvider(), providersById.keySet());
            return matches;
        }

        List<RerankCandidate> candidates = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            candidates.add(new RerankCandidate(String.valueOf(i), match.embedded().text(), match.score()));
        }


        try {
            List<RerankedCandidate> reranked = provider.rerank(query, candidates, matches.size());
            List<EmbeddingMatch<TextSegment>> reordered = mapBackToMatches(matches, reranked);
            for (int i = 0; i < Math.min(5, matches.size()); i++) {
                var m = matches.get(i);
                log.info("{} : score={} source={} text={}",
                        i,
                        m.score(),
                        m.embedded().metadata().getString("source"),
                        m.embedded().text().substring(0, Math.min(120, m.embedded().text().length())));
            }
            return reordered;
        } catch (Exception e) {
            if (properties.isFailOpen()) {
                log.warn("⚠️ Reranker '{}' failed ({}) — falling back to original vector-similarity order",
                        provider.id(), e.getMessage());
                return matches;
            }
            throw e;
        }
    }

    /** Maps RerankedCandidate.id() back to the original EmbeddingMatch objects, preserving the provider's order. */
    private List<EmbeddingMatch<TextSegment>> mapBackToMatches(
            List<EmbeddingMatch<TextSegment>> matches, List<RerankedCandidate> reranked) {

        Map<String, EmbeddingMatch<TextSegment>> byId = new HashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            byId.put(String.valueOf(i), matches.get(i));
        }

        List<EmbeddingMatch<TextSegment>> reordered = reranked.stream()
                .map(rc -> byId.get(rc.id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Safety net: if the provider dropped any candidates (e.g. a malformed/partial response),
        // append whatever's missing at the end rather than silently losing context.
        if (reordered.size() < matches.size()) {
            Set<String> seen = reranked.stream().map(RerankedCandidate::id).collect(Collectors.toSet());
            for (int i = 0; i < matches.size(); i++) {
                if (!seen.contains(String.valueOf(i))) {
                    reordered.add(matches.get(i));
                }
            }
        }
        return reordered;
    }
}

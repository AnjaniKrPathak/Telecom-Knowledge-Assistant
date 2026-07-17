package com.rag.service;

import com.rag.config.ConversationMemoryProperties;
import com.rag.config.RerankerProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QueryCacheService queryCacheService;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationMemoryProperties memoryProperties;
    private final FeedbackService feedbackService;
    private final RerankerService rerankerService;
    private final RerankerProperties rerankerProperties;

    @Value("${rag.max-results}")
    private int maxResults;

    /** Stateless overload — kept for callers that don't care about conversation memory. */
    public RagResponse query(String question) {
        return query(question, null);
    }

    /**
     * Runs one RAG turn scoped to {@code sessionId}: prior turns in that session are folded
     * into the prompt as conversation history (so follow-up questions like "what about the
     * second one?" resolve correctly), retrieved chunks are re-ranked using accumulated
     * feedback (see FeedbackService), and both the question and the answer are persisted to
     * conversation memory before returning.
     *
     * @param sessionId caller-supplied or previously-issued session id; a new one is minted if null/blank
     */
    public RagResponse query(String question, String sessionId) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? conversationMemoryService.newSessionId()
                : sessionId;
        log.info("RAG query [session={}]: {}", effectiveSessionId, question);

        boolean isFollowUp = memoryProperties.isEnabled() && conversationMemoryService.hasHistory(effectiveSessionId);
        conversationMemoryService.addUserMessage(effectiveSessionId, question);
        log.info("Session {} isFollowUp={} (memory.enabled={}, memory.persist={}) -> cache {}",
                effectiveSessionId, isFollowUp, memoryProperties.isEnabled(), memoryProperties.isPersist(),
                isFollowUp ? "SKIPPED" : "checked");

        // 0. Cache check — only for the FIRST turn of a session. Once conversation history
        // exists, the same question text can legitimately need a different answer depending
        // on context, so follow-ups always go through retrieval + generation.
        if (!isFollowUp) {
            Optional<RagResponse> cached = queryCacheService.get(question);
            if (cached.isPresent()) {
                return finalizeCachedResponse(cached.get(), effectiveSessionId);
            }
        }

        // 1. Embed the question
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Retrieve candidates from pgvector. When reranking is on, cast a wider net
        // (rag.reranker.candidate-pool-size) so the reranker has real choices to make, then trim
        // down to rag.max-results after reranking + feedback-boosting below.
        int fetchSize = rerankerProperties.isEnabled()
                ? Math.max(rerankerProperties.getCandidatePoolSize(), maxResults)
                : maxResults;

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(fetchSize)
                .minScore(0.5)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        log.info("Retrieved {} candidate chunks (fetchSize={})", matches.size(), fetchSize);

        // 2a. Cross-encoder / LLM reranking — reorders by actual relevance to the question,
        // which vector cosine-similarity alone often gets wrong for nuanced queries.
        matches = rerankerService.rerank(question, matches);

        // 2b. Feedback-aware re-ranking — nudge chunks from historically thumbs-up'd sources
        // up, and thumbs-down'd sources down, before building the context/answer.
        matches = applyFeedbackRanking(matches);

        // 2c. Trim down to the final context size now that reranking + feedback have both had a say.
        if (matches.size() > maxResults) {
            matches = matches.subList(0, maxResults);
        }

        if (matches.isEmpty()) {
            String noAnswer = "I don't have enough information in the knowledge base to answer this question.";
            String interactionId = conversationMemoryService.addAssistantMessage(effectiveSessionId, noAnswer, List.of());
            return new RagResponse(question, noAnswer, List.of(), effectiveSessionId, interactionId);
        }

        // 3. Build context from retrieved chunks
        String context = matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3b. Conversation history (if any) so the model can resolve follow-up references.
        String history = conversationMemoryService.buildHistoryContext(effectiveSessionId);

        // 4. Build RAG prompt
        String prompt = buildPrompt(question, context, history);

        // 5. Generate answer using llama3
        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
        String answer = response.content().text();
        log.info("Generated answer (length={})", answer.length());

        // 6. Build source references
        List<SourceReference> sources = matches.stream()
                .map(match -> new SourceReference(
                        match.embedded().metadata().getString("source"),
                        match.embedded().metadata().getString("type"),
                        match.score(),
                        match.embedded().text().substring(0, Math.min(200, match.embedded().text().length())) + "..."
                ))
                .collect(Collectors.toList());

        List<String> sourceNames = sources.stream()
                .map(SourceReference::source)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        String interactionId = conversationMemoryService.addAssistantMessage(effectiveSessionId, answer, sourceNames);

        RagResponse ragResponse = new RagResponse(question, answer, sources, effectiveSessionId, interactionId);

        // Only cache first-turn (context-free) answers — see cache check above.
        if (!isFollowUp) {
            queryCacheService.put(question, ragResponse);
        }

        return ragResponse;
    }

    /** Cached answers are shared across sessions, so mint a fresh sessionId/interactionId per use and still log the turn. */
    private RagResponse finalizeCachedResponse(RagResponse cached, String sessionId) {
        List<String> sourceNames = cached.sources().stream()
                .map(SourceReference::source)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        String interactionId = conversationMemoryService.addAssistantMessage(sessionId, cached.answer(), sourceNames);
        return new RagResponse(cached.question(), cached.answer(), cached.sources(), sessionId, interactionId);
    }

    /**
     * Nudges the current order (already best-first, from the reranker or raw vector search) using
     * accumulated feedback. Deliberately does NOT resort by {@code match.score()} — that's the raw
     * cosine score, which reranking cannot change (EmbeddingMatch is immutable), so sorting by it
     * here would silently undo whatever the reranker just did. Instead each candidate gets a
     * normalized [0,1] "rank score" from its CURRENT position, and feedback's small boost/penalty
     * (capped by rag.feedback.max-boost) nudges from there — enough to reorder close calls, not
     * enough to override a strong reranker signal.
     */
    private List<EmbeddingMatch<TextSegment>> applyFeedbackRanking(List<EmbeddingMatch<TextSegment>> matches) {
        int n = matches.size();
        if (n <= 1) {
            return matches;
        }
        return java.util.stream.IntStream.range(0, n)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> {
                    double rankScore = 1.0 - (i / (double) n);
                    String source = matches.get(i).embedded().metadata().getString("source");
                    return rankScore + feedbackService.sourceBoost(source);
                }).reversed())
                .map(matches::get)
                .collect(Collectors.toList());
    }

    private String buildPrompt(String question, String context, String history) {
        String historyBlock = (history == null || history.isBlank())
                ? ""
                : "Conversation so far (for resolving references like \"it\"/\"that\"/\"the second one\" — " +
                  "do not treat this as source material):\n" + history + "\n\n";

        return """
                You are a helpful assistant. Use ONLY the context below to answer the question.
                If the answer is not in the context, say "I don't know based on the provided documents."
                
                %sContext:
                %s
                
                Question: %s
                
                Answer:
                """.formatted(historyBlock, context, question);
    }

    // ── Response DTOs ────────────────────────────────────────────────────────
    public record RagResponse(String question, String answer, List<SourceReference> sources,
                               String sessionId, String interactionId) {}

    public record SourceReference(String source, String type, double score, String excerpt) {}
}

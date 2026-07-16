package com.rag.service;

import com.rag.config.ConversationMemoryProperties;
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

        // 2. Retrieve top-k relevant chunks from pgvector
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(0.5)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        log.info("Retrieved {} relevant chunks", matches.size());

        // 2b. Feedback-aware re-ranking — nudge chunks from historically thumbs-up'd sources
        // up, and thumbs-down'd sources down, before building the context/answer.
        matches = applyFeedbackRanking(matches);

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

    /** Re-orders matches by (original cosine score + accumulated feedback boost for that chunk's source). */
    private List<EmbeddingMatch<TextSegment>> applyFeedbackRanking(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> match) -> {
                    String source = match.embedded().metadata().getString("source");
                    return match.score() + feedbackService.sourceBoost(source);
                }).reversed())
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

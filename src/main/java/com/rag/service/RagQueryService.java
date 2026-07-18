package com.rag.service;

import com.rag.config.ConversationMemoryProperties;
import com.rag.config.RerankerProperties;
import com.rag.search.QueryTypeClassifier;
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

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

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
    private final QueryTypeClassifier queryTypeClassifier;

    @Value("${rag.max-results}")
    private int maxResults;

    /** Stateless overload — kept for callers that don't care about conversation memory. */
    public RagResponse query(String question) {
        return query(question, null, QueryProgressListener.NOOP);
    }

    /** Session-aware overload — kept for callers (REST API) that don't need live progress callbacks. */
    public RagResponse query(String question, String sessionId) {
        return query(question, sessionId, QueryProgressListener.NOOP);
    }

    /**
     * Runs one RAG turn scoped to {@code sessionId}: prior turns in that session are folded
     * into the prompt as conversation history (so follow-up questions like "what about the
     * second one?" resolve correctly), retrieved chunks are re-ranked using accumulated
     * feedback (see FeedbackService), and both the question and the answer are persisted to
     * conversation memory before returning.
     * <p>
     * {@code listener} gets called at each pipeline stage (cache check, retrieval, generation)
     * so a caller like the Webex bot can show live "thinking → searching → drafting" progress
     * instead of the room sitting on one long silent wait. Purely observational — it never
     * changes what gets retrieved or answered.
     *
     * @param sessionId caller-supplied or previously-issued session id; a new one is minted if null/blank
     */
    public RagResponse query(String question, String sessionId, QueryProgressListener listener) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? conversationMemoryService.newSessionId()
                : sessionId;
        log.info("RAG query [session={}]: {}", effectiveSessionId, question);
        listener.onCacheChecking();

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
                listener.onCacheHit();
                return finalizeCachedResponse(cached.get(), effectiveSessionId);
            }
        }

        // 1. Embed the question
        listener.onSearching();
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Retrieve candidates from pgvector. When reranking is on, cast a wider net
        // (rag.reranker.candidate-pool-size) so the reranker has real choices to make, then trim
        // down to rag.max-results after reranking + feedback-boosting below.
        int fetchSize = rerankerProperties.isEnabled()
                ? Math.max(rerankerProperties.getCandidatePoolSize(), maxResults)
                : maxResults;

        // 2a. Metadata pre-filter — if the question clearly reads as a structured-data lookup
        // ("Flat Offering", "Offering ID", "External ID", "TUTI"...) or as narrative documentation
        // ("What is...", "How does...", "Explain..."), narrow the vector search to just that
        // document type instead of searching across everything. See QueryTypeClassifier.
        Optional<String> typeFilter = queryTypeClassifier.classify(question);

        var searchRequestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(fetchSize)
                .minScore(0.5);
        typeFilter.ifPresent(type -> searchRequestBuilder.filter(metadataKey("type").isEqualTo(type)));

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequestBuilder.build()).matches();
        log.info("Retrieved {} candidate chunks (fetchSize={}, typeFilter={})",
                matches.size(), fetchSize, typeFilter.orElse("none"));

        // Fallback: a type filter that returns nothing (misclassified question, or that type
        // genuinely has no relevant content) shouldn't dead-end the query — retry unfiltered.
        if (matches.isEmpty() && typeFilter.isPresent()) {
            log.info("No matches for type={} filter — falling back to unfiltered search", typeFilter.get());
            EmbeddingSearchRequest fallbackRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)
                    .maxResults(fetchSize)
                    .minScore(0.5)
                    .build();
            matches = embeddingStore.search(fallbackRequest).matches();
        }

        // 2b. Cross-encoder / LLM reranking — reorders by actual relevance to the question,
        // which vector cosine-similarity alone often gets wrong for nuanced queries.
        matches = rerankerService.rerank(question, matches);

        // 2c. Feedback-aware re-ranking — nudge chunks from historically thumbs-up'd sources
        // up, and thumbs-down'd sources down, before building the context/answer.
        matches = applyFeedbackRanking(matches);

        // 2d. Trim down to the final context size now that reranking + feedback have both had a say.
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
        listener.onGenerating(matches.size());
        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
        String answer = response.content().text();
        log.info("Generated answer (length={})", answer.length());

        // 6. Build source references — includes structured metadata (sheet/row, business fields
        // like offeringName/flatOfferingId/externalId/tuti when present) so it's obvious from the
        // response alone WHY a given chunk was retrieved, without cross-referencing the source file.
        List<SourceReference> sources = matches.stream()
                .map(match -> new SourceReference(
                        match.embedded().metadata().getString("source"),
                        match.embedded().metadata().getString("type"),
                        match.score(),
                        match.embedded().text().substring(0, Math.min(200, match.embedded().text().length())) + "...",
                        extractDebugMetadata(match.embedded().metadata())
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

    public record SourceReference(String source, String type, double score, String excerpt,
                                   java.util.Map<String, String> metadata) {}

    /**
     * Pulls the metadata keys worth surfacing for debugging retrieval — structural (sheet, rows)
     * and any of the configured business fields (offeringName, flatOfferingId, externalId, tuti,
     * ...) that ExcelStructuredChunker put on this chunk — so it's visible in the response why a
     * given row was matched, instead of having to go dig through the source spreadsheet.
     */
    private java.util.Map<String, String> extractDebugMetadata(dev.langchain4j.data.document.Metadata metadata) {
        java.util.Map<String, String> debug = new java.util.LinkedHashMap<>();
        for (String key : DEBUG_METADATA_KEYS) {
            String value = metadata.getString(key);
            if (value != null && !value.isBlank()) {
                debug.put(key, value);
            }
        }
        return debug;
    }

    private static final List<String> DEBUG_METADATA_KEYS = List.of(
            "workbook", "sheet", "rowStart", "rowEnd",
            "offeringName", "flatOfferingId", "offeringId", "externalId", "tuti"
    );

    /**
     * Staged progress callback so a caller (the Webex bot) can show live "thinking → searching
     * → drafting" status instead of one long silent wait. All methods are default no-ops;
     * implement only the stages you want to react to. Purely observational.
     */
    public interface QueryProgressListener {
        QueryProgressListener NOOP = new QueryProgressListener() {};

        /** About to check the query cache (the very first thing a fresh turn does). */
        default void onCacheChecking() {}

        /** A cached answer was found and is being returned as-is — no retrieval/generation ran. */
        default void onCacheHit() {}

        /** About to embed the question and search the vector store. */
        default void onSearching() {}

        /** Retrieval (+ reranking + feedback ranking) is done; about to call the LLM to draft the answer. */
        default void onGenerating(int sourceCount) {}
    }
}

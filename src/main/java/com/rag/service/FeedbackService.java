package com.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.FeedbackProperties;
import com.rag.model.ConversationMessage;
import com.rag.model.FeedbackRating;
import com.rag.model.QueryFeedback;
import com.rag.repository.ConversationMessageRepository;
import com.rag.repository.QueryFeedbackRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records thumbs up / thumbs down feedback against a specific RAG answer (identified by
 * interactionId) and turns the accumulated votes into a small per-source score adjustment
 * that RagQueryService applies when ranking retrieved chunks — this is the "feedback loop
 * improves retrieval" half of the feature: documents that keep getting thumbs-down sink in
 * the ranking, documents that keep getting thumbs-up float up.
 * <p>
 * Per-source net-vote counts are kept in an in-memory map (rebuilt from Postgres at startup)
 * so the adjustment can be applied on every query without a DB round trip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final QueryFeedbackRepository feedbackRepository;
    private final ConversationMessageRepository conversationRepository;
    private final FeedbackProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** source name -> running (upVotes, downVotes) */
    private final Map<String, VoteCounts> sourceVotes = new ConcurrentHashMap<>();

    @PostConstruct
    void loadExistingFeedback() {
        List<QueryFeedback> all = feedbackRepository.findAll();
        for (QueryFeedback fb : all) {
            applyVoteToMemory(fb.getSourcesJson(), fb.getRating());
        }
        log.info("👍👎 Feedback loop initialized — {} historical votes loaded across {} sources",
                all.size(), sourceVotes.size());
    }

    public enum SubmitResult { OK, NOT_FOUND, ALREADY_RATED }

    public record SubmitOutcome(SubmitResult result, QueryFeedback feedback) {}

    /**
     * Attaches or replaces the free-text comment on an already-submitted feedback entry, without
     * touching its rating or the in-memory vote counts. Used for the "rate now, comment box shows
     * afterward" flow (REST) and the Webex "want to tell me more?" follow-up prompt.
     */
    public SubmitOutcome addComment(String interactionId, String comment) {
        Optional<QueryFeedback> existing = feedbackRepository.findByInteractionId(interactionId);
        if (existing.isEmpty()) {
            return new SubmitOutcome(SubmitResult.NOT_FOUND, null);
        }
        QueryFeedback feedback = existing.get();
        feedback.setComment(comment);
        QueryFeedback saved = feedbackRepository.save(feedback);
        log.info("💬 Comment added to feedback for interaction {}", interactionId);
        return new SubmitOutcome(SubmitResult.OK, saved);
    }

    /**
     * Submits (or updates) feedback for one answer. Looks up the ConversationMessage behind
     * {@code interactionId} to recover the question/answer/sources it was built from.
     */
    public SubmitOutcome submit(String interactionId, FeedbackRating rating, String comment) {
        Optional<ConversationMessage> messageOpt = conversationRepository.findByInteractionId(interactionId);
        if (messageOpt.isEmpty()) {
            return new SubmitOutcome(SubmitResult.NOT_FOUND, null);
        }
        ConversationMessage message = messageOpt.get();

        Optional<QueryFeedback> existing = feedbackRepository.findByInteractionId(interactionId);
        if (existing.isPresent()) {
            // Allow correcting a vote: undo the old one in memory, then apply the new one.
            QueryFeedback old = existing.get();
            undoVoteInMemory(old.getSourcesJson(), old.getRating());
            old.setRating(rating);
            old.setComment(comment);
            old.setCreatedAt(LocalDateTime.now());
            QueryFeedback saved = feedbackRepository.save(old);
            applyVoteToMemory(saved.getSourcesJson(), rating);
            log.info("🔄 Feedback updated for interaction {} -> {}", interactionId, rating);
            return new SubmitOutcome(SubmitResult.OK, saved);
        }

        String question = findPrecedingUserQuestion(message);
        QueryFeedback feedback = QueryFeedback.builder()
                .interactionId(interactionId)
                .sessionId(message.getSessionId())
                .question(question)
                .answer(message.getContent())
                .rating(rating)
                .comment(comment)
                .sourcesJson(message.getSourcesJson())
                .createdAt(LocalDateTime.now())
                .build();
        QueryFeedback saved = feedbackRepository.save(feedback);
        applyVoteToMemory(saved.getSourcesJson(), rating);
        log.info("{} Feedback recorded for interaction {}", rating == FeedbackRating.UP ? "👍" : "👎", interactionId);
        return new SubmitOutcome(SubmitResult.OK, saved);
    }

    /**
     * Score adjustment (roughly in the same range as a cosine-similarity delta) to add to a
     * chunk's retrieval score based on how its source has historically been rated. Positive
     * for sources with more thumbs-up, negative for more thumbs-down, zero for no/neutral
     * feedback or when the feature is disabled.
     */
    public double sourceBoost(String source) {
        if (!properties.isEnabled() || source == null) {
            return 0.0;
        }
        VoteCounts counts = sourceVotes.get(source);
        if (counts == null) {
            return 0.0;
        }
        int net = counts.up.get() - counts.down.get();
        if (Math.abs(net) < properties.getMinVotesForAdjustment()) {
            return 0.0;
        }
        double raw = net * properties.getBoostWeight();
        double cap = properties.getMaxBoost();
        return Math.max(-cap, Math.min(cap, raw));
    }

    public Map<String, Object> stats() {
        long up = feedbackRepository.countByRating(FeedbackRating.UP);
        long down = feedbackRepository.countByRating(FeedbackRating.DOWN);
        Map<String, Object> perSource = new ConcurrentHashMap<>();
        sourceVotes.forEach((source, counts) -> perSource.put(source, Map.of(
                "up", counts.up.get(),
                "down", counts.down.get(),
                "netScore", counts.up.get() - counts.down.get(),
                "retrievalBoost", sourceBoost(source)
        )));
        return Map.of(
                "totalUp", up,
                "totalDown", down,
                "sources", perSource
        );
    }

    private String findPrecedingUserQuestion(ConversationMessage assistantMessage) {
        List<ConversationMessage> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(assistantMessage.getSessionId());
        ConversationMessage lastUser = null;
        for (ConversationMessage m : history) {
            if (m.getCreatedAt() != null && assistantMessage.getCreatedAt() != null
                    && m.getCreatedAt().isAfter(assistantMessage.getCreatedAt())) {
                break;
            }
            if ("USER".equals(m.getRole())) {
                lastUser = m;
            }
            if (m.getId().equals(assistantMessage.getId())) {
                break;
            }
        }
        return lastUser != null ? lastUser.getContent() : null;
    }

    private void applyVoteToMemory(String sourcesJson, FeedbackRating rating) {
        for (String source : parseSources(sourcesJson)) {
            VoteCounts counts = sourceVotes.computeIfAbsent(source, s -> new VoteCounts());
            if (rating == FeedbackRating.UP) {
                counts.up.incrementAndGet();
            } else {
                counts.down.incrementAndGet();
            }
        }
    }

    private void undoVoteInMemory(String sourcesJson, FeedbackRating rating) {
        for (String source : parseSources(sourcesJson)) {
            VoteCounts counts = sourceVotes.get(source);
            if (counts == null) {
                continue;
            }
            if (rating == FeedbackRating.UP) {
                counts.up.decrementAndGet();
            } else {
                counts.down.decrementAndGet();
            }
        }
    }

    private List<String> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final class VoteCounts {
        final AtomicInteger up = new AtomicInteger();
        final AtomicInteger down = new AtomicInteger();
    }
}

package com.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.ConversationMemoryProperties;
import com.rag.model.ConversationMessage;
import com.rag.repository.ConversationMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns per-session conversation history so RagQueryService can resolve follow-up questions
 * ("what about the second one?") and so each answer can be traced back to an interactionId
 * for feedback (see FeedbackService).
 * <p>
 * A "session" is just a string key — the REST API generates one per conversation (or accepts
 * one the caller already has), and the Webex bot uses the room id directly, so every Webex
 * space gets a running conversation automatically with no extra setup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final ConversationMessageRepository repository;
    private final ConversationMemoryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Generates a fresh session id for callers that don't already have one. */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    public void addUserMessage(String sessionId, String content) {
        if (!properties.isEnabled() || !properties.isPersist()) {
            return;
        }
        repository.save(ConversationMessage.builder()
                .sessionId(sessionId)
                .role("USER")
                .content(content)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Persists the assistant's answer and returns the interactionId used to later attach feedback. */
    public String addAssistantMessage(String sessionId, String content, List<String> sources) {
        String interactionId = UUID.randomUUID().toString();
        if (!properties.isEnabled() || !properties.isPersist()) {
            return interactionId;
        }
        String sourcesJson = toJson(sources);
        repository.save(ConversationMessage.builder()
                .sessionId(sessionId)
                .role("ASSISTANT")
                .content(content)
                .interactionId(interactionId)
                .sourcesJson(sourcesJson)
                .createdAt(LocalDateTime.now())
                .build());
        return interactionId;
    }

    /**
     * Builds a plain-text "Conversation so far" block from the last {@code rag.memory.max-turns}
     * turn pairs, oldest first, truncated to {@code rag.memory.max-history-chars}. Returns "" when
     * memory is disabled or this is the first turn in the session.
     */
    public String buildHistoryContext(String sessionId) {
        if (!properties.isEnabled() || sessionId == null) {
            return "";
        }
        // maxTurns pairs => up to maxTurns * 2 messages
        int limit = Math.max(properties.getMaxTurns(), 0) * 2;
        if (limit == 0) {
            return "";
        }
        List<ConversationMessage> recentDesc =
                repository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, limit));
        if (recentDesc.isEmpty()) {
            return "";
        }
        List<ConversationMessage> recentAsc = new java.util.ArrayList<>(recentDesc);
        Collections.reverse(recentAsc);

        StringBuilder sb = new StringBuilder();
        for (ConversationMessage m : recentAsc) {
            String speaker = "USER".equals(m.getRole()) ? "User" : "Assistant";
            sb.append(speaker).append(": ").append(m.getContent()).append("\n");
        }

        String history = sb.toString().trim();
        int max = properties.getMaxHistoryChars();
        if (history.length() > max) {
            // keep the most recent context, drop the oldest lines from the front
            history = "...\n" + history.substring(history.length() - max);
        }
        return history;
    }

    /** Whether this session already has at least one prior turn (used to decide cache eligibility). */
    public boolean hasHistory(String sessionId) {
        if (sessionId == null || !properties.isEnabled()) {
            return false;
        }
        return repository.countBySessionId(sessionId) > 0;
    }

    public List<ConversationMessage> getFullHistory(String sessionId) {
        return repository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void clearSession(String sessionId) {
        repository.deleteBySessionId(sessionId);
        log.info("🧹 Cleared conversation history for session {}", sessionId);
    }

    /** Latest interactionId for a session — lets a Webex "👍"/"👎" reply target the bot's last answer. */
    public Optional<String> latestInteractionId(String sessionId) {
        return repository.findLatestAssistantMessages(sessionId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(ConversationMessage::getInteractionId);
    }

    public Optional<ConversationMessage> findByInteractionId(String interactionId) {
        return repository.findByInteractionId(interactionId);
    }

    /** Nightly cleanup of conversation turns older than rag.memory.retention-days. */
    @Scheduled(cron = "0 30 2 * * *")
    public void purgeExpiredHistory() {
        if (!properties.isEnabled() || !properties.isPersist()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        long removed = repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("🧹 Purged {} conversation turns older than {} days", removed, properties.getRetentionDays());
        }
    }

    private String toJson(List<String> sources) {
        try {
            return objectMapper.writeValueAsString(sources == null ? List.of() : sources);
        } catch (Exception e) {
            log.debug("Could not serialize sources for conversation memory: {}", e.getMessage());
            return "[]";
        }
    }
}

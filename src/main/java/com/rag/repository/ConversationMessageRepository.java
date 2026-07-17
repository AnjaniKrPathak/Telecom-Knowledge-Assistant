package com.rag.repository;

import com.rag.model.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** Most recent messages first — used to pull just the last N turns for prompt history. */
    List<ConversationMessage> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    Optional<ConversationMessage> findByInteractionId(String interactionId);

    /** Latest ASSISTANT row for a session — lets Webex "👍/👎" replies target the last answer. */
    @Query("SELECT m FROM ConversationMessage m WHERE m.sessionId = :sessionId AND m.role = 'ASSISTANT' " +
            "ORDER BY m.createdAt DESC")
    List<ConversationMessage> findLatestAssistantMessages(@Param("sessionId") String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}

package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single turn (either the user's question or the assistant's answer) in a conversation.
 * <p>
 * Rows are grouped by {@code sessionId} — for the REST API this is a caller-supplied or
 * server-generated UUID; for Webex it is simply the Webex room id, so every space
 * automatically gets its own running conversation with zero manual setup.
 * <p>
 * Assistant rows additionally carry {@code interactionId} (used to attach feedback via
 * {@code POST /api/feedback}) and {@code sourcesJson} (the "source" metadata values used to
 * build that answer, so thumbs up/down can be attributed back to specific documents/chunks
 * for feedback-aware retrieval — see {@code FeedbackService}).
 */
@Entity
@Table(name = "conversation_messages", indexes = {
        @Index(name = "idx_conv_session_created", columnList = "sessionId, createdAt"),
        @Index(name = "idx_conv_interaction", columnList = "interactionId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 200)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role; // USER | ASSISTANT

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Only set on ASSISTANT rows — correlates this answer with feedback submitted later. */
    @Column(length = 64)
    private String interactionId;

    /** Only set on ASSISTANT rows — JSON array of the "source" values used to build the answer. */
    @Column(columnDefinition = "TEXT")
    private String sourcesJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

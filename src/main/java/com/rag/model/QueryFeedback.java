package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single thumbs up / thumbs down submitted against one specific RAG answer.
 * <p>
 * {@code interactionId} ties this back to the {@link ConversationMessage} (ASSISTANT row) that
 * produced the answer, so we know exactly which source chunks were used — this is what lets
 * {@code FeedbackService} turn accumulated votes into a per-source retrieval boost/penalty
 * ("feedback loop improves retrieval").
 */
@Entity
@Table(name = "query_feedback", indexes = {
        @Index(name = "idx_feedback_interaction", columnList = "interactionId"),
        @Index(name = "idx_feedback_session", columnList = "sessionId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 64)
    private String interactionId;

    @Column(length = 200)
    private String sessionId;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedbackRating rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    /** JSON array of the "source" values used to build the rated answer (copied from the ConversationMessage). */
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

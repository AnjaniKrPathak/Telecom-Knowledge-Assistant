package com.rag.controller;

import com.rag.model.FeedbackRating;
import com.rag.model.QueryFeedback;
import com.rag.service.FeedbackService;
import com.rag.service.FeedbackService.SubmitOutcome;
import com.rag.service.FeedbackService.SubmitResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Thumbs up / thumbs down on a specific RAG answer.
 * <p>
 * Every answer returned by /api/query (and every Webex bot reply) carries an
 * {@code interactionId}; submit it here with a rating to record feedback. Accumulated votes
 * are folded back into retrieval ranking automatically (see FeedbackService) — no manual
 * re-tuning required.
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "Thumbs up / thumbs down on RAG answers, feeding back into retrieval ranking")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @Operation(summary = "Rate an answer — body: { \"interactionId\": \"...\", \"rating\": \"UP\"|\"DOWN\", \"comment\": \"optional\" }")
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body) {
        String interactionId = body.get("interactionId");
        String ratingRaw = body.get("rating");
        String comment = body.get("comment");

        if (interactionId == null || interactionId.isBlank() || ratingRaw == null || ratingRaw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Both \"interactionId\" and \"rating\" (UP or DOWN) are required."));
        }

        FeedbackRating rating;
        try {
            rating = FeedbackRating.valueOf(ratingRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "\"rating\" must be UP or DOWN."));
        }

        SubmitOutcome outcome = feedbackService.submit(interactionId, rating, comment);
        if (outcome.result() == SubmitResult.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "No answer found for interactionId '" + interactionId + "'."));
        }

        QueryFeedback saved = outcome.feedback();
        return ResponseEntity.ok(Map.of(
                "interactionId", saved.getInteractionId(),
                "rating", saved.getRating(),
                "recorded", true
        ));
    }

    @PatchMapping("/{interactionId}/comment")
    @Operation(summary = "Attach/replace the free-text comment on an existing rating — " +
            "for a \"rate now, comment box appears after\" UI. Body: { \"comment\": \"...\" }")
    public ResponseEntity<?> addComment(@PathVariable String interactionId, @RequestBody Map<String, String> body) {
        String comment = body.get("comment");
        if (comment == null || comment.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "\"comment\" is required."));
        }
        SubmitOutcome outcome = feedbackService.addComment(interactionId, comment);
        if (outcome.result() == SubmitResult.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "No feedback found for interactionId '" + interactionId + "'. Submit a rating first via POST /api/feedback."));
        }
        return ResponseEntity.ok(Map.of(
                "interactionId", outcome.feedback().getInteractionId(),
                "comment", outcome.feedback().getComment(),
                "recorded", true
        ));
    }

    @GetMapping("/stats")
    @Operation(summary = "Aggregated thumbs up/down totals, plus the per-source retrieval boost each document currently carries")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(feedbackService.stats());
    }
}

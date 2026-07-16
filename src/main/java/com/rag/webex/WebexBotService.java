package com.rag.webex;

import com.rag.config.FeedbackProperties;
import com.rag.model.FeedbackRating;
import com.rag.service.ConversationMemoryService;
import com.rag.service.FeedbackService;
import com.rag.service.FeedbackService.SubmitOutcome;
import com.rag.service.RagQueryService;
import com.rag.service.RagQueryService.RagResponse;
import com.rag.webex.dto.WebexAttachmentAction;
import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexWebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridges Webex messages to the existing RAG pipeline (RagQueryService).
 *
 * Handles two kinds of webhook events:
 *
 * A) "messages / created" — a user sent a message in a connected space:
 *   1. Ignore events triggered by the bot's own messages (avoids reply loops)
 *   2. Fetch the full message text via WebexClient (webhooks only carry the message id)
 *   3. Strip any @mention markup from the text
 *   4a. If this reply answers a pending "want to add a comment?" prompt, record it as a comment.
 *   4b. Else if the text is just a thumbs-up/thumbs-down (e.g. "👍", "+1", "not helpful") — a
 *       fallback for clients that don't render card buttons — treat it as feedback on the bot's
 *       LAST answer in that room.
 *   4c. Otherwise, call RagQueryService.query(question, roomId) — same call used by /api/query,
 *       but scoped to this room so follow-up questions resolve using conversation history.
 *   5. Send the answer back as an Adaptive Card with inline 👍 Like / 👎 Dislike buttons — the
 *      user just taps one, no need to type anything.
 *
 * B) "attachmentActions / created" — a user tapped one of those Like/Dislike buttons:
 *   Resolve which button + interactionId via WebexClient.getAttachmentAction, submit the rating,
 *   then ask (as a normal chat message) whether they'd like to add a comment.
 *
 * The Webex room id doubles as the conversation-memory sessionId, so every space automatically
 * gets its own running conversation with zero manual configuration. Both webhooks are kept in
 * sync automatically by WebexWebhookService — no manual Developer Portal steps either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebexBotService {

    private static final Set<String> THUMBS_UP = Set.of(
            "👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿", "+1", "thumbsup", "thumbs up",
            "good answer", "helpful", "great answer");
    private static final Set<String> THUMBS_DOWN = Set.of(
            "👎", "👎🏻", "👎🏼", "👎🏽", "👎🏾", "👎🏿", "-1", "thumbsdown", "thumbs down",
            "bad answer", "not helpful", "unhelpful", "wrong answer");
    private static final Set<String> SKIP_COMMENT = Set.of(
            "skip", "no", "no thanks", "nope", "nvm", "none", "nothing", "cancel", "n/a");

    /** roomId -> pending "want to add a comment?" prompt awaiting the user's next reply. */
    private final ConcurrentMap<String, PendingComment> pendingComments = new ConcurrentHashMap<>();

    private final WebexClient webexClient;
    private final WebexProperties webexProperties;
    private final RagQueryService ragQueryService;
    private final ConversationMemoryService conversationMemoryService;
    private final FeedbackService feedbackService;
    private final FeedbackProperties feedbackProperties;

    @Async
    public void handleWebhookEvent(WebexWebhookEvent event) {
        try {
            if ("attachmentActions".equals(event.getResource()) && "created".equals(event.getEvent())) {
                handleAttachmentAction(event.getData());
                return;
            }

            if (!"messages".equals(event.getResource()) || !"created".equals(event.getEvent())) {
                log.debug("Ignoring unhandled event: {}/{}", event.getResource(), event.getEvent());
                return;
            }

            WebexWebhookEvent.WebhookData data = event.getData();
            if (data == null || data.getId() == null) {
                log.warn("Webhook event missing data.id, skipping");
                return;
            }

            // Avoid the bot answering its own messages
            if (webexClient.isFromBot(data.getPersonId())) {
                return;
            }
            WebexMessage message =  null;

            if ("test-id".equals(event.getData().getId())) {
                // Use event.getData().getText() directly
            } else {
                message = webexClient.getMessage(event.getData().getId());
                log.info("message" + message);
            }
            // WebexMessage message = webexClient.getMessage(data.getId());
            String question = cleanQuestion(message.getText());


            if (question == null || question.isBlank()) {
                webexClient.sendMessageToRoom(message.getRoomId(),
                        "I didn't catch a question there — ask me anything about the ingested documents.");
                return;
            }

            String roomId = message.getRoomId();

            // If we just asked "want to add a comment?" for this room, this reply answers that
            // instead of being a new question or a fresh feedback vote.
            PendingComment pending = pendingComments.remove(roomId);
            if (pending != null && pending.isStillValid()) {
                handlePendingCommentReply(roomId, pending.interactionId(), question);
                return;
            }

            // Text feedback fallback: a bare "👍"/"👎" (or a few text equivalents) rates the last
            // answer — useful for clients that don't render the inline card buttons below.
            if (feedbackProperties.isWebexTextShortcutsEnabled()) {
                Optional<FeedbackRating> shortcut = detectFeedbackShortcut(question);
                if (shortcut.isPresent()) {
                    Optional<String> interactionId = conversationMemoryService.latestInteractionId(roomId);
                    if (interactionId.isEmpty()) {
                        webexClient.sendMessageToRoom(roomId,
                                "I don't have a recent answer from me in this space to rate yet — ask me something first!");
                        return;
                    }
                    submitFeedbackAndPromptComment(roomId, interactionId.get(), shortcut.get());
                    return;
                }
            }

            // log.info("Webex question from {}: {}", message.getPersonEmail(), question);

            RagResponse ragResponse = ragQueryService.query(question, roomId);
            String replyText = formatReply(ragResponse);
            if (webexProperties.isAttachmentActionsWebhookEnabled()) {
                Map<String, Object> card = buildFeedbackCard(replyText, ragResponse.interactionId());
                if (message != null) {
                    webexClient.sendCardToRoom(message.getRoomId(), replyText, card);
                } else {
                    webexClient.sendCardToRoom("Y2lzY29zcGFyazovL3VzL1BFT1BMRS9hODg0M2QzMS1lMmQ0LTQ0MmUtYTU0OC0xM2I0MzUyZmU1MzU", replyText, card);
                }
            } else {
                if (message != null) {
                    webexClient.sendMessageToRoom(message.getRoomId(), replyText);
                } else {
                    webexClient.sendMessageToRoom("Y2lzY29zcGFyazovL3VzL1BFT1BMRS9hODg0M2QzMS1lMmQ0LTQ0MmUtYTU0OC0xM2I0MzUyZmU1MzU", replyText);
                }
            }


        } catch (Exception e) {
            log.error("Error handling Webex webhook event", e);
        }
    }

    /** Handles a tap on one of the inline 👍 Like / 👎 Dislike buttons attached to an answer. */
    private void handleAttachmentAction(WebexWebhookEvent.WebhookData data) {
        if (data == null || data.getId() == null) {
            log.warn("attachmentActions event missing data.id, skipping");
            return;
        }
        if (webexClient.isFromBot(data.getPersonId())) {
            return;
        }

        WebexAttachmentAction action = webexClient.getAttachmentAction(data.getId());
        if (action == null || action.getInputs() == null) {
            log.warn("Could not resolve attachment action {}", data.getId());
            return;
        }

        Object interactionIdValue = action.getInputs().get("interactionId");
        Object ratingValue = action.getInputs().get("rating");
        if (interactionIdValue == null || ratingValue == null) {
            log.warn("Attachment action {} missing interactionId/rating in inputs: {}", data.getId(), action.getInputs());
            return;
        }

        FeedbackRating rating;
        try {
            rating = FeedbackRating.valueOf(ratingValue.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown rating '{}' from attachment action {}", ratingValue, data.getId());
            return;
        }

        String roomId = action.getRoomId() != null ? action.getRoomId() : data.getRoomId();
        submitFeedbackAndPromptComment(roomId, interactionIdValue.toString(), rating);
    }

    /** Strips the "@BotName" mention markup Webex includes when a user @-mentions the bot in a group space. */
    private String cleanQuestion(String text) {
        if (text == null) return null;
        return text.replaceAll("^@\\S+\\s*", "").trim();
    }

    private Optional<FeedbackRating> detectFeedbackShortcut(String text) {
        String normalized = text.trim().toLowerCase();
        if (THUMBS_UP.contains(normalized)) {
            return Optional.of(FeedbackRating.UP);
        }
        if (THUMBS_DOWN.contains(normalized)) {
            return Optional.of(FeedbackRating.DOWN);
        }
        return Optional.empty();
    }

    /** Shared by both the tap-a-button flow and the type-👍/👎 fallback: records the vote, then offers a comment box. */
    private void submitFeedbackAndPromptComment(String roomId, String interactionId, FeedbackRating rating) {
        SubmitOutcome outcome = feedbackService.submit(interactionId, rating, null);
        if (outcome.result() == FeedbackService.SubmitResult.NOT_FOUND) {
            webexClient.sendMessageToRoom(roomId, "I couldn't find that answer to rate — it may have expired.");
            return;
        }
        String ack = rating == FeedbackRating.UP
                ? "👍 Thanks for the feedback — glad that helped! Want to add a comment about what worked? Reply with it, or say \"skip\"."
                : "👎 Thanks for the feedback — sorry that missed the mark. Want to tell me what went wrong? Reply with a comment, or say \"skip\".";
        webexClient.sendMessageToRoom(roomId, ack);

        int ttlMinutes = feedbackProperties.getWebexCommentPromptTtlMinutes();
        pendingComments.put(roomId, new PendingComment(
                interactionId, Instant.now().plusSeconds(Math.max(ttlMinutes, 1) * 60L)));
    }

    /** Handles the user's reply to the "want to add a comment?" prompt sent after a thumbs rating. */
    private void handlePendingCommentReply(String roomId, String interactionId, String reply) {
        String normalized = reply == null ? "" : reply.trim().toLowerCase();
        if (normalized.isEmpty() || SKIP_COMMENT.contains(normalized)) {
            webexClient.sendMessageToRoom(roomId, "No problem, thanks again!");
            return;
        }
        SubmitOutcome outcome = feedbackService.addComment(interactionId, reply.trim());
        if (outcome.result() == FeedbackService.SubmitResult.NOT_FOUND) {
            webexClient.sendMessageToRoom(roomId, "Hmm, I couldn't attach that comment — the rating may have expired.");
            return;
        }
        webexClient.sendMessageToRoom(roomId, "💬 Got it, thanks for the extra detail!");
    }

    /** A "want to add a comment?" prompt awaiting the user's next reply, with a short expiry so a later, unrelated message isn't mistaken for a comment. */
    private record PendingComment(String interactionId, Instant expiresAt) {
        boolean isStillValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /**
     * Adaptive Card carrying the answer text plus two Action.Submit buttons. Webex fires an
     * "attachmentActions / created" webhook (handled above) with the tapped button's "data"
     * payload — {@code {interactionId, rating}} — as soon as someone taps Like or Dislike, so
     * no extra typing is needed.
     */
    private Map<String, Object> buildFeedbackCard(String answerText, String interactionId) {
        return Map.of(
                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                "type", "AdaptiveCard",
                "version", "1.3",
                "body", List.of(
                        Map.of("type", "TextBlock", "text", answerText, "wrap", true)
                ),
                "actions", List.of(
                        Map.of("type", "Action.Submit", "title", "👍 Like",
                                "data", Map.of("interactionId", interactionId, "rating", "UP")),
                        Map.of("type", "Action.Submit", "title", "👎 Dislike",
                                "data", Map.of("interactionId", interactionId, "rating", "DOWN"))
                )
        );
    }

    private String formatReply(RagResponse ragResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append(ragResponse.answer());

        if (ragResponse.sources() != null && !ragResponse.sources().isEmpty()) {
            sb.append("\n\n**Sources:**\n");
            ragResponse.sources().forEach(src -> {
                sb.append("- ").append(src.source()).append(" (").append(src.type());
               /* if (src.location() != null) {
                    sb.append(" — ").append(src.location());
                }*/
                sb.append(")\n");
            });
        }
        // Only mention the text shortcut when card buttons are off — otherwise the buttons speak for themselves.
        if (!webexProperties.isAttachmentActionsWebhookEnabled() && feedbackProperties.isWebexTextShortcutsEnabled()) {
            sb.append("\n\n_Reply 👍 or 👎 to rate this answer._");
        }
        return sb.toString();
    }
}

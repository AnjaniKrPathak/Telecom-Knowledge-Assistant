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
import java.util.concurrent.ConcurrentLinkedDeque;
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

    /**
     * Webex webhook data.id -> when it was processed. Webex's own delivery isn't guaranteed
     * exactly-once (their docs say the same event can be redelivered), so every incoming event is
     * de-duplicated by this id before any processing happens — otherwise a redelivered event would
     * answer the same question a second time.
     */
    private final ConcurrentMap<String, Instant> recentlyProcessedEventIds = new ConcurrentHashMap<>();
    private static final long EVENT_DEDUP_TTL_SECONDS = 300; // 5 minutes

    /**
     * roomId -> timestamps of recent replies, used purely as a circuit breaker against a runaway
     * reply loop — most plausibly the bot replying to its own messages if
     * {@code WebexClient#isFromBot} can't recognize them (e.g. bot identity resolution failed at
     * startup and never recovered), but this guard trips on ANY cause of rapid repeated replies
     * into the same room, not just that one. Once tripped, the room is paused for
     * {@code LOOP_GUARD_WINDOW_SECONDS} rather than the bot continuing to answer indefinitely.
     */
    private final ConcurrentMap<String, ConcurrentLinkedDeque<Instant>> recentRepliesByRoom = new ConcurrentHashMap<>();
    private static final int MAX_REPLIES_PER_WINDOW = 5;
    private static final long LOOP_GUARD_WINDOW_SECONDS = 60;

    private final WebexClient webexClient;
    private final WebexProperties webexProperties;
    private final RagQueryService ragQueryService;
    private final ConversationMemoryService conversationMemoryService;
    private final FeedbackService feedbackService;
    private final FeedbackProperties feedbackProperties;

    @Async
    public void handleWebhookEvent(WebexWebhookEvent event) {
        try {
            WebexWebhookEvent.WebhookData data = event.getData();
            String eventId = data != null ? data.getId() : null;

            // Webex delivery isn't guaranteed exactly-once — drop anything we've already handled
            // recently instead of answering the same question again.
            if (eventId != null && !markProcessedIfNew(eventId)) {
                log.info("Ignoring duplicate Webex webhook event (data.id={}) — already processed within the last {}s",
                        eventId, EVENT_DEDUP_TTL_SECONDS);
                return;
            }

            // If we don't currently know our own identity, we CANNOT safely tell "a real user's
            // message" apart from "the bot's own reply" — proceeding here risks exactly the
            // self-reply loop this whole method exists to prevent. Refuse to process until
            // WebexClient's retry-with-backoff (or a restart) resolves it.
            if (!webexClient.isBotIdentityResolved()) {
                log.error("Refusing to process Webex webhook event — bot identity is not resolved, so incoming " +
                        "messages can't be distinguished from the bot's own replies. See WebexClient startup logs.");
                return;
            }

            if ("attachmentActions".equals(event.getResource()) && "created".equals(event.getEvent())) {
                handleAttachmentAction(event.getData());
                return;
            }

            if (!"messages".equals(event.getResource()) || !"created".equals(event.getEvent())) {
                log.debug("Ignoring unhandled event: {}/{}", event.getResource(), event.getEvent());
                return;
            }

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
            String roomId = message.getRoomId();

            // Circuit breaker: if this room has already received an unusual number of replies in
            // the last minute, stop here rather than risk answering forever — see the field
            // javadoc on recentRepliesByRoom for why this matters regardless of root cause.
            if (tooManyRepliesRecently(roomId)) {
                log.error("Possible reply loop detected in Webex room {} ({} replies in the last {}s) — " +
                                "pausing auto-replies to this room for now. Check whether the bot's own " +
                                "identity resolved correctly (see WebexClient startup logs) if this persists.",
                        roomId, MAX_REPLIES_PER_WINDOW, LOOP_GUARD_WINDOW_SECONDS);
                return;
            }

            if (question == null || question.isBlank()) {
                webexClient.sendMessageToRoom(roomId,
                        "I didn't catch a question there — ask me anything about the ingested documents.");
                recordReply(roomId);
                return;
            }

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
                        recordReply(roomId);
                        return;
                    }
                    submitFeedbackAndPromptComment(roomId, interactionId.get(), shortcut.get());
                    return;
                }
            }

            // log.info("Webex question from {}: {}", message.getPersonEmail(), question);

            answerAndReply(roomId, question);
            recordReply(roomId);

        } catch (Exception e) {
            log.error("Error handling Webex webhook event", e);
        }
    }

    /** Marks {@code eventId} as processed if it hasn't been seen in the last {@code EVENT_DEDUP_TTL_SECONDS}; returns false for a duplicate. */
    private boolean markProcessedIfNew(String eventId) {
        Instant cutoff = Instant.now().minusSeconds(EVENT_DEDUP_TTL_SECONDS);
        recentlyProcessedEventIds.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        return recentlyProcessedEventIds.putIfAbsent(eventId, Instant.now()) == null;
    }

    /** True if {@code roomId} has already received {@code MAX_REPLIES_PER_WINDOW}+ replies in the last {@code LOOP_GUARD_WINDOW_SECONDS}. */
    private boolean tooManyRepliesRecently(String roomId) {
        Instant cutoff = Instant.now().minusSeconds(LOOP_GUARD_WINDOW_SECONDS);
        ConcurrentLinkedDeque<Instant> timestamps =
                recentRepliesByRoom.computeIfAbsent(roomId, r -> new ConcurrentLinkedDeque<>());
        timestamps.removeIf(t -> t.isBefore(cutoff));
        return timestamps.size() >= MAX_REPLIES_PER_WINDOW;
    }

    /** Records that a reply was just sent into {@code roomId}, for the {@link #tooManyRepliesRecently} circuit breaker. */
    private void recordReply(String roomId) {
        recentRepliesByRoom.computeIfAbsent(roomId, r -> new ConcurrentLinkedDeque<>()).add(Instant.now());
    }

    /**
     * Runs the RAG query for {@code question} and sends the reply into {@code roomId}, same as
     * before — with one addition: if {@code webex.thinking-status-enabled} is on, a "🤔
     * Thinking..." placeholder is posted first and live-edited ("🔎 Searching...", "📚
     * Drafting...") via {@link WebexQueryProgress} while the pipeline runs, then removed once
     * the real answer/card has been sent, so the space shows progress instead of sitting silent.
     */
    private void answerAndReply(String roomId, String question) {
        String placeholderId = null;
        if (webexProperties.isThinkingStatusEnabled()) {
            WebexMessage placeholder = webexClient.sendMessageToRoom(roomId, "🤔 Thinking about your question...");
            placeholderId = placeholder != null ? placeholder.getId() : null;
        }
        RagQueryService.QueryProgressListener progress = placeholderId == null
                ? RagQueryService.QueryProgressListener.NOOP
                : new WebexQueryProgress(webexClient, roomId, placeholderId);

        RagResponse ragResponse = ragQueryService.query(question, roomId, progress);
        String replyText = formatReply(ragResponse);
        if (webexProperties.isAttachmentActionsWebhookEnabled()) {
            Map<String, Object> card = buildFeedbackCard(replyText, ragResponse.interactionId());
            webexClient.sendCardToRoom(roomId, replyText, card);
        } else {
            webexClient.sendMessageToRoom(roomId, replyText);
        }

        if (placeholderId != null) {
            webexClient.deleteMessage(placeholderId);
        }
    }

    /**
     * Turns RagQueryService's staged callbacks into live edits of the Webex placeholder message,
     * so the room sees "thinking → searching → drafting" instead of one long silent wait.
     * Edit failures are logged and swallowed — a missed status update should never break the
     * actual answer from being generated and sent.
     */
    private static class WebexQueryProgress implements RagQueryService.QueryProgressListener {
        private final WebexClient webexClient;
        private final String roomId;
        private final String messageId;

        WebexQueryProgress(WebexClient webexClient, String roomId, String messageId) {
            this.webexClient = webexClient;
            this.roomId = roomId;
            this.messageId = messageId;
        }

        @Override
        public void onCacheHit() {
            edit("⚡ I've answered something like this recently — pulling up that answer...");
        }

        @Override
        public void onSearching() {
            edit("🔎 Searching the knowledge base for relevant documents...");
        }

        @Override
        public void onGenerating(int sourceCount) {
            edit("📚 Found " + sourceCount + " relevant source" + (sourceCount == 1 ? "" : "s")
                    + " — drafting an answer...");
        }

        private void edit(String text) {
            try {
                webexClient.editMessage(messageId, roomId, text);
            } catch (Exception e) {
                log.debug("Could not update Webex thinking message: {}", e.getMessage());
            }
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
            recordReply(roomId);
            return;
        }
        String ack = rating == FeedbackRating.UP
                ? "👍 Thanks for the feedback — glad that helped! Want to add a comment about what worked? Reply with it, or say \"skip\"."
                : "👎 Thanks for the feedback — sorry that missed the mark. Want to tell me what went wrong? Reply with a comment, or say \"skip\".";
        webexClient.sendMessageToRoom(roomId, ack);
        recordReply(roomId);

        int ttlMinutes = feedbackProperties.getWebexCommentPromptTtlMinutes();
        pendingComments.put(roomId, new PendingComment(
                interactionId, Instant.now().plusSeconds(Math.max(ttlMinutes, 1) * 60L)));
    }

    /** Handles the user's reply to the "want to add a comment?" prompt sent after a thumbs rating. */
    private void handlePendingCommentReply(String roomId, String interactionId, String reply) {
        String normalized = reply == null ? "" : reply.trim().toLowerCase();
        if (normalized.isEmpty() || SKIP_COMMENT.contains(normalized)) {
            webexClient.sendMessageToRoom(roomId, "No problem, thanks again!");
            recordReply(roomId);
            return;
        }
        SubmitOutcome outcome = feedbackService.addComment(interactionId, reply.trim());
        if (outcome.result() == FeedbackService.SubmitResult.NOT_FOUND) {
            webexClient.sendMessageToRoom(roomId, "Hmm, I couldn't attach that comment — the rating may have expired.");
            recordReply(roomId);
            return;
        }
        webexClient.sendMessageToRoom(roomId, "💬 Got it, thanks for the extra detail!");
        recordReply(roomId);
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

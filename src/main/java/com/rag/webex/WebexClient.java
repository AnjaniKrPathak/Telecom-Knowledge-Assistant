package com.rag.webex;

import com.rag.webex.dto.WebexAttachmentAction;
import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexPerson;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client around the Webex REST API (https://webexapis.com/v1).
 *
 * Responsibilities:
 *  - authenticate every call with the bot's Bearer token
 *  - fetch a message's full content by id (webhooks only give you the id)
 *  - send a reply back into a room
 *  - resolve the bot's own identity, so incoming events from the bot
 *    itself can be ignored (otherwise it could reply to itself forever)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebexClient {

    @Qualifier("webexRestTemplate")
    private final RestTemplate webexRestTemplate;
    private final WebexProperties webexProperties;

    private volatile WebexPerson botIdentity;

    @PostConstruct
    public void init() {
        resolveBotIdentityWithRetry();
    }

    /**
     * Resolves the bot's own identity with a few retries (short backoff) instead of giving up
     * after one failed attempt. This matters a lot more than a typical "nice to have" retry: while
     * {@code botIdentity} is null, {@link #isFromBot} can never return true, which means the bot
     * cannot tell its own replies apart from a real user's messages — every reply it posts would
     * itself trigger a new "messages/created" webhook event that looks exactly like a fresh
     * question, and {@code WebexBotService} would answer it, triggering another reply, forever.
     * A single transient failure to reach the Webex API at startup (slow network, brief outage)
     * would otherwise cause exactly that runaway self-reply loop for the rest of the process's
     * lifetime, with no way to recover short of a restart.
     */
    private void resolveBotIdentityWithRetry() {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                this.botIdentity = getMyOwnIdentity();
                log.info("Webex bot connected as '{}' ({})", botIdentity.getDisplayName(), botIdentity.getId());
                return;
            } catch (Exception e) {
                log.warn("Could not resolve Webex bot identity (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("Webex bot identity could NOT be resolved after {} attempts. Until this succeeds, isFromBot() " +
                        "will always return false, meaning the bot cannot recognize its own messages — this risks " +
                        "an endless self-reply loop if the bot ever posts into a room it's also listening to. " +
                        "Check webex.bot-token and network access to {}, then restart the app.",
                maxAttempts, webexProperties.getApiBaseUrl());
    }

    /** GET /v1/people/me — resolves the identity tied to the configured bot token. */
    public WebexPerson getMyOwnIdentity() {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        return webexRestTemplate.exchange(
                webexProperties.getApiBaseUrl() + "/people/me",
                HttpMethod.GET,
                request,
                WebexPerson.class
        ).getBody();
    }

    /** GET /v1/messages/{messageId} — fetches the full message content referenced by a webhook event. */
    public WebexMessage getMessage(String messageId) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        log.info("API URL: {}", webexProperties.getApiBaseUrl() + "/messages/" + messageId);
        log.info("Message ID: {}", messageId);
        return webexRestTemplate.exchange(
                webexProperties.getApiBaseUrl() + "/messages/" + messageId,
                HttpMethod.GET,
                request,
                WebexMessage.class
        ).getBody();
    }

    /** POST /v1/messages — sends a plain-text reply into an existing room (group space or 1:1). */
    public WebexMessage sendMessageToRoom(String roomId, String text) {
        Map<String, Object> body = new HashMap<>();
        log.info("URL = {}", webexProperties.getApiBaseUrl() + "/messages");
        log.info("Room ID = {}", roomId);
        log.info("Message = {}", text);
        body.put("roomId", roomId);
        body.put("markdown", text);
        return postMessage(body);
    }

    /** POST /v1/messages — sends a direct message to a person by email (creates a 1:1 room if needed). */
    public WebexMessage sendMessageToPerson(String personEmail, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("toPersonEmail", personEmail);
        body.put("markdown", text);
        return postMessage(body);
    }

    /**
     * POST /v1/messages with an Adaptive Card attachment — renders as tappable buttons (e.g. inline
     * 👍 Like / 👎 Dislike under an answer) in Webex clients that support cards. {@code fallbackText}
     * is sent alongside as the plain "markdown" field for clients/notifications that don't render cards.
     */
    public WebexMessage sendCardToRoom(String roomId, String fallbackText, Map<String, Object> card) {
        Map<String, Object> body = new HashMap<>();
        body.put("roomId", roomId);
        body.put("markdown", fallbackText);
        body.put("attachments", List.of(Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", card
        )));
        return postMessage(body);
    }

    /** GET /v1/attachment/actions/{id} — resolves which Action.Submit button was tapped and the "data" it carried. */
    public WebexAttachmentAction getAttachmentAction(String actionId) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        return webexRestTemplate.exchange(
                webexProperties.getApiBaseUrl() + "/attachment/actions/" + actionId,
                HttpMethod.GET,
                request,
                WebexAttachmentAction.class
        ).getBody();
    }

    /**
     * PUT /v1/messages/{id} — edits a message's text in place. Used to turn a single
     * "🤔 Thinking..." placeholder into a live status line ("🔎 Searching...", "📚 Drafting...")
     * as the RAG pipeline progresses, instead of the room sitting on one long silent wait.
     */
    public WebexMessage editMessage(String messageId, String roomId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("roomId", roomId);
        body.put("markdown", text);

        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            return webexRestTemplate.exchange(
                    webexProperties.getApiBaseUrl() + "/messages/" + messageId,
                    HttpMethod.PUT,
                    request,
                    WebexMessage.class
            ).getBody();
        } catch (RestClientException e) {
            log.warn("Failed to edit Webex message {}: {}", messageId, e.getMessage());
            throw e;
        }
    }

    /** DELETE /v1/messages/{id} — removes the "thinking..." placeholder once the real answer/card has been sent. */
    public void deleteMessage(String messageId) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        try {
            webexRestTemplate.exchange(
                    webexProperties.getApiBaseUrl() + "/messages/" + messageId,
                    HttpMethod.DELETE,
                    request,
                    Void.class
            );
        } catch (RestClientException e) {
            log.debug("Could not delete Webex placeholder message {}: {}", messageId, e.getMessage());
        }
    }

    private WebexMessage postMessage(Map<String, Object> body) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            return webexRestTemplate.postForObject(
                    webexProperties.getApiBaseUrl() + "/messages",
                    request,
                    WebexMessage.class
            );
        } catch (RestClientException e) {
            log.error("Failed to send Webex message: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * GET /v1/rooms — lists every room/space the bot currently belongs to. Used by the
     * broadcast feature ({@code allKnownRooms: true}) to push a message out to every space
     * without the caller having to enumerate roomIds by hand. Webex caps page size at 1000,
     * which is plenty for a bot's rooms in the common case.
     */
    public List<com.rag.webex.dto.WebexRoom> getMyRooms() {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        try {
            com.rag.webex.dto.WebexRoom.ListResponse response = webexRestTemplate.exchange(
                    webexProperties.getApiBaseUrl() + "/rooms?max=1000",
                    HttpMethod.GET,
                    request,
                    com.rag.webex.dto.WebexRoom.ListResponse.class
            ).getBody();
            return response != null && response.getItems() != null
                    ? response.getItems()
                    : List.of();
        } catch (RestClientException e) {
            log.error("Failed to list Webex rooms: {}", e.getMessage(), e);
            throw e;
        }
    }

    /** True if this personId belongs to the bot itself (used to avoid the bot replying to itself). */
    public boolean isFromBot(String personId) {
        return botIdentity != null && botIdentity.getId().equals(personId);
    }

    /**
     * False means {@link #isFromBot} can never return true right now (identity not yet resolved,
     * or all retries at startup failed) — callers that reply into rooms should treat this as "do
     * not risk auto-replying" rather than assuming every incoming message is from a real user.
     */
    public boolean isBotIdentityResolved() {
        return botIdentity != null;
    }

    public String getBotId() {
        return botIdentity != null ? botIdentity.getId() : null;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(webexProperties.getBotToken());
        return headers;
    }
}

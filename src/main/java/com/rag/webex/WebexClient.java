package com.rag.webex;

import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexPerson;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
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

    private final RestTemplate webexRestTemplate;
    private final WebexProperties webexProperties;

    private volatile WebexPerson botIdentity;

    @PostConstruct
    public void init() {
        try {
            this.botIdentity = getMyOwnIdentity();
            log.info("Webex bot connected as '{}' ({})",
                    botIdentity.getDisplayName(), botIdentity.getId());
        } catch (Exception e) {
            log.warn("Could not resolve Webex bot identity at startup. " +
                    "Check webex.bot-token. Cause: {}", e.getMessage());
        }
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

    /** True if this personId belongs to the bot itself (used to avoid the bot replying to itself). */
    public boolean isFromBot(String personId) {
        return botIdentity != null && botIdentity.getId().equals(personId);
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

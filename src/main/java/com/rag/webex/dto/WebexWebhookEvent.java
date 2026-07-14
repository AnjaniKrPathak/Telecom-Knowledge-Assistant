package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mirrors the payload Webex POSTs to your registered webhook URL.
 * https://developer.webex.com/docs/api/guides/webhooks
 *
 * Example:
 * {
 *   "id": "...",
 *   "name": "telecom-bot-webhook",
 *   "resource": "messages",
 *   "event": "created",
 *   "data": {
 *     "id": "<messageId>",
 *     "roomId": "...",
 *     "personId": "...",
 *     "personEmail": "..."
 *   }
 * }
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexWebhookEvent {
    private String id;
    private String name;
    private String resource; // "messages"
    private String event;    // "created"
    private WebhookData data;

    @lombok.Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebhookData {
        private String id;       // the messageId — fetch full message via GET /v1/messages/{id}
        private String roomId;
        private String roomType;
        private String personId;
        private String personEmail;
    }
}

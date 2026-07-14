package com.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mirrors the fields we care about from Webex's webhook object
 * (https://developer.webex.com/docs/api/v1/webhooks).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookItem {
    private String id;
    private String name;
    private String targetUrl;
    private String resource;
    private String event;
    /** "active" or "inactive" */
    private String status;
    /** ISO-8601 creation timestamp, used to pick the oldest webhook when de-duplicating. */
    private String created;
}

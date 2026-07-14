package com.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for PUT /webhooks/{id}. Webex only accepts name/targetUrl/secret/status on update
 * (resource/event can't be changed once a webhook is created), so nulls are omitted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateWebhookRequest {
    private String name;

    private String targetUrl;

    private String resource;

    private String event;

    private String secret;
}

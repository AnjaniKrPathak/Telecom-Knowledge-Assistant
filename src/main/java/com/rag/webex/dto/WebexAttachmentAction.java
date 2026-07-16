package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * GET /v1/attachment/actions/{id} — resolves the details of an Action.Submit button tap on an
 * Adaptive Card, referenced by the "attachmentActions" webhook event's data.id.
 * <p>
 * {@code inputs} carries whatever the card's "data" object held for the tapped button — for the
 * inline Like/Dislike buttons built in WebexBotService, that's {@code {interactionId, rating}}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexAttachmentAction {
    private String id;
    private String type;      // "submit"
    private String messageId; // the card message this action was submitted against
    private String roomId;
    private String personId;
    private Map<String, Object> inputs;
}

package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Mirrors the Webex "person" resource, used for GET /v1/people/me
 * to discover the bot's own id/email so it can ignore its own messages.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexPerson {
    private String id;
    private List<String> emails;
    private String displayName;
}

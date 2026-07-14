package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Mirrors the Webex "message" resource.
 * https://developer.webex.com/docs/api/v1/messages
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexMessage {
    private String id;
    private String roomId;
    private String roomType;      // "direct" or "group"
    private String text;
    private String markdown;
    private String personId;
    private String personEmail;
    private String toPersonEmail; // used when sending a 1:1 message
    private List<String> mentionedPeople;
    private String created;
}

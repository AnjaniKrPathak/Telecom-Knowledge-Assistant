package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Mirrors the Webex "room" resource for GET /v1/rooms — used by the broadcast feature to
 * discover every space the bot currently belongs to (id/title/type), so a message can be
 * pushed out to all of them without the caller having to know each roomId up front.
 * https://developer.webex.com/docs/api/v1/rooms
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexRoom {
    private String id;
    private String title;
    private String type; // "direct" or "group"

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListResponse {
        private List<WebexRoom> items;
    }
}

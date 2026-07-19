package com.rag.webex.dto;

import lombok.Data;

import java.util.List;

/**
 * Payload for POST /api/webex/broadcast — lets the bot PUSH a message straight to a list of
 * people and/or rooms in one call, instead of waiting for someone to message it first.
 * <p>
 * Webex has no native "send to everyone" call, so this fans a single message out across
 * every target you supply:
 * <ul>
 *   <li>{@code personEmails} — 1:1 direct messages. Webex auto-creates the 1:1 room if the
 *       bot has never messaged that person before, so no existing chat is required.</li>
 *   <li>{@code roomIds} — existing group spaces / rooms the bot is a member of.</li>
 *   <li>{@code allKnownRooms} — if true, also sends to every room the bot currently belongs
 *       to (fetched live via GET /v1/rooms), on top of anything listed in roomIds.</li>
 * </ul>
 * At least one of personEmails / roomIds / allKnownRooms must resolve to a non-empty target list.
 */
@Data
public class BroadcastRequest {

    /** The message body. Sent as Webex "markdown", so basic markdown formatting is supported. */
    private String message;

    /** Person emails to DM directly — no prior 1:1 conversation required. */
    private List<String> personEmails;

    /** Existing room/space ids to post into. */
    private List<String> roomIds;

    /** If true, also broadcasts to every room the bot is currently a member of. */
    private boolean allKnownRooms;
}

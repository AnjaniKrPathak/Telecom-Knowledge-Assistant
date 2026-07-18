package com.rag.webex.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Per-target outcome of a broadcast send — one entry per email/room in the request. */
@Data
@AllArgsConstructor
public class BroadcastResult {

    /** "PERSON" or "ROOM". */
    private String targetType;

    /** The personEmail or roomId that was messaged. */
    private String target;

    private boolean success;

    /** The resulting Webex message id, when successful. */
    private String messageId;

    /** Failure reason, when unsuccessful. */
    private String error;
}

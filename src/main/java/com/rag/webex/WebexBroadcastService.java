package com.rag.webex;

import com.rag.webex.dto.BroadcastRequest;
import com.rag.webex.dto.BroadcastResult;
import com.rag.webex.dto.BroadcastSummary;
import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexRoom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lets the bot PUSH a message to many people/rooms at once, on demand — e.g. from an admin
 * screen, a scheduled job, or a curl call — instead of only ever replying inside a room/1:1
 * chat that a user opened first.
 * <p>
 * Every send reuses the exact same Webex "POST /v1/messages" call WebexClient already makes
 * for normal replies ({@code toPersonEmail} for a direct message, {@code roomId} for a space).
 * Webex auto-creates the 1:1 room the first time the bot messages a person, so no existing
 * chat needs to exist beforehand — this is what makes a true "broadcast" possible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebexBroadcastService {

    private final WebexClient webexClient;
    private final WebexProperties webexProperties;

    /**
     * Sends {@code request.getMessage()} to every person email, every explicit roomId, and
     * (if {@code allKnownRooms} is set) every room the bot currently belongs to. Failures on
     * one target never abort the rest of the batch — every target is attempted and the
     * per-target outcome is returned so the caller can see exactly what landed and what didn't.
     */
    public BroadcastSummary broadcast(BroadcastRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("Broadcast message must not be blank");
        }

        Set<String> personEmails = request.getPersonEmails() == null
                ? Set.of()
                : new LinkedHashSet<>(request.getPersonEmails());
        Set<String> roomIds = request.getRoomIds() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(request.getRoomIds());

        if (request.isAllKnownRooms()) {
            for (WebexRoom room : webexClient.getMyRooms()) {
                roomIds.add(room.getId());
            }
        }

        if (personEmails.isEmpty() && roomIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No broadcast targets resolved — supply personEmails, roomIds, and/or allKnownRooms=true");
        }

        List<BroadcastResult> results = new ArrayList<>(personEmails.size() + roomIds.size());

        for (String email : personEmails) {
            results.add(sendOne("PERSON", email, () -> webexClient.sendMessageToPerson(email, request.getMessage())));
            pace();
        }
        for (String roomId : roomIds) {
            results.add(sendOne("ROOM", roomId, () -> webexClient.sendMessageToRoom(roomId, request.getMessage())));
            pace();
        }

        long succeeded = results.stream().filter(BroadcastResult::isSuccess).count();
        log.info("Webex broadcast finished: {}/{} sends succeeded", succeeded, results.size());

        return new BroadcastSummary(results.size(), (int) succeeded, results.size() - (int) succeeded, results);
    }

    private BroadcastResult sendOne(String targetType, String target, SendCall call) {
        try {
            WebexMessage sent = call.send();
            return new BroadcastResult(targetType, target, true, sent != null ? sent.getId() : null, null);
        } catch (Exception e) {
            log.warn("Broadcast send failed for {} {}: {}", targetType, target, e.getMessage());
            return new BroadcastResult(targetType, target, false, null, e.getMessage());
        }
    }

    /** Small pause between sends so a large broadcast doesn't trip Webex's per-second rate limits. */
    private void pace() {
        long delayMs = webexProperties.getBroadcastDelayMs();
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface SendCall {
        WebexMessage send();
    }
}

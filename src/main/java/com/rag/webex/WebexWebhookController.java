package com.rag.webex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.webex.dto.BroadcastRequest;
import com.rag.webex.dto.BroadcastSummary;
import com.rag.webex.dto.WebexWebhookEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the webhook Webex calls whenever a new message is posted
 * in a space the bot belongs to (event: "messages" / "created").
 *
 * Register this URL with Webex once, e.g.:
 *
 * curl -X POST https://webexapis.com/v1/webhooks \
 *   -H "Authorization: Bearer $BOT_TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *         "name": "telecom-bot-webhook",
 *         "targetUrl": "https://your-public-host/api/webex/webhook",
 *         "resource": "messages",
 *         "event": "created",
 *         "secret": "<same value as webex.webhook-secret>"
 *       }'
 */

@RestController
@RequestMapping("/api/webex")
@RequiredArgsConstructor
@Tag(name = "Webex Chatbot", description = "Webhook endpoint connecting the RAG knowledge base to Webex")
public class WebexWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebexWebhookController.class);
    private final WebexBotService webexBotService;
    private final WebexSignatureValidator signatureValidator;
    private final ObjectMapper objectMapper;
    private final WebexBroadcastService webexBroadcastService;

    @PostMapping("/webhook")
    @Operation(summary = "Webex webhook receiver — triggered on every new message in a connected space")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Spark-Signature", required = false) String signature) {

        if (!signatureValidator.isValid(rawBody, signature)) {
            log.warn("Rejected Webex webhook with invalid signature");

          return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            WebexWebhookEvent event = objectMapper.readValue(rawBody, WebexWebhookEvent.class);
            // Respond to Webex immediately; the actual RAG query + reply happens async
            log.info("Call Webex method to acknowledge receipt of the message");
            webexBotService.handleWebhookEvent(event);
        } catch (Exception e) {
            log.error("Failed to parse Webex webhook payload", e);
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Proactively PUSHES a message to one or more people/rooms — the bot messages them first,
     * with no prior chat required (Webex auto-creates the 1:1 room for each new personEmail).
     * This is separate from {@code /webhook} above, which only ever REPLIES inside a room after
     * a user messages the bot first.
     *
     * Example:
     * curl -X POST https://your-public-host/api/webex/broadcast \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *         "message": "🔔 Maintenance window tonight 10pm-12am IST — the knowledge base bot will be briefly unavailable.",
     *         "personEmails": ["alice@example.com", "bob@example.com"],
     *         "roomIds": [],
     *         "allKnownRooms": false
     *       }'
     */
    @PostMapping("/broadcast")
    @Operation(summary = "Broadcast a message to a list of people/rooms, or every room the bot belongs to, without an incoming chat first")
    public ResponseEntity<?> broadcast(@RequestBody BroadcastRequest request) {
        try {
            BroadcastSummary summary = webexBroadcastService.broadcast(request);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected broadcast request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Broadcast failed", e);
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", "Broadcast failed: " + e.getMessage()));
        }
    }
}

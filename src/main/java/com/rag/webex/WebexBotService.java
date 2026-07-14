package com.rag.webex;

import com.rag.service.RagQueryService;
import com.rag.service.RagQueryService.RagResponse;
import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexWebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Bridges Webex messages to the existing RAG pipeline (RagQueryService).
 *
 * Flow for every "messages / created" webhook event:
 *   1. Ignore events triggered by the bot's own messages (avoids reply loops)
 *   2. Fetch the full message text via WebexClient (webhooks only carry the message id)
 *   3. Strip any @mention markup from the text
 *   4. Call RagQueryService.query(question) — same call used by /api/query
 *   5. Format the answer (+ source references) and send it back to the room
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebexBotService {

    private final WebexClient webexClient;
    private final RagQueryService ragQueryService;

    @Async
    public void handleWebhookEvent(WebexWebhookEvent event) {
        try {
            if (!"messages".equals(event.getResource()) || !"created".equals(event.getEvent())) {
                log.debug("Ignoring non-message-created event: {}/{}", event.getResource(), event.getEvent());
                return;
            }

            WebexWebhookEvent.WebhookData data = event.getData();
            if (data == null || data.getId() == null) {
                log.warn("Webhook event missing data.id, skipping");
                return;
            }

            // Avoid the bot answering its own messages
            if (webexClient.isFromBot(data.getPersonId())) {
                return;
            }
            WebexMessage message =  null;

            if ("test-id".equals(event.getData().getId())) {
                // Use event.getData().getText() directly
            } else {
                 message = webexClient.getMessage(event.getData().getId());
                 log.info("message" + message);
            }
           // WebexMessage message = webexClient.getMessage(data.getId());
           String question = cleanQuestion(message.getText());


            if (question == null || question.isBlank()) {
                webexClient.sendMessageToRoom(message.getRoomId(),
                        "I didn't catch a question there — ask me anything about the ingested documents.");
                return;
            }

           // log.info("Webex question from {}: {}", message.getPersonEmail(), question);

            RagResponse ragResponse = ragQueryService.query(question);
            String reply = formatReply(ragResponse);
           if(message != null ){
               webexClient.sendMessageToRoom(message.getRoomId(), reply);
           }else{
               webexClient.sendMessageToRoom("Y2lzY29zcGFyazovL3VzL1BFT1BMRS9hODg0M2QzMS1lMmQ0LTQ0MmUtYTU0OC0xM2I0MzUyZmU1MzU", reply);
           }


        } catch (Exception e) {
            log.error("Error handling Webex webhook event", e);
        }
    }

    /** Strips the "@BotName" mention markup Webex includes when a user @-mentions the bot in a group space. */
    private String cleanQuestion(String text) {
        if (text == null) return null;
        return text.replaceAll("^@\\S+\\s*", "").trim();
    }

    private String formatReply(RagResponse ragResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append(ragResponse.answer());

        if (ragResponse.sources() != null && !ragResponse.sources().isEmpty()) {
            sb.append("\n\n**Sources:**\n");
            ragResponse.sources().forEach(src -> {
                sb.append("- ").append(src.source()).append(" (").append(src.type());
                if (src.location() != null) {
                    sb.append(" — ").append(src.location());
                }
                sb.append(")\n");
            });
        }
        return sb.toString();
    }
}

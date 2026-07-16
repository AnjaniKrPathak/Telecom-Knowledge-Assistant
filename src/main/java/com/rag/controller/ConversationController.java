package com.rag.controller;

import com.rag.model.ConversationMessage;
import com.rag.service.ConversationMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read/clear the conversation history behind a given sessionId (REST sessionId or Webex roomId). */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation Memory", description = "Inspect or clear per-session chat history used for follow-up questions")
public class ConversationController {

    private final ConversationMemoryService conversationMemoryService;

    @GetMapping("/{sessionId}")
    @Operation(summary = "Full turn-by-turn history for a session, oldest first")
    public ResponseEntity<List<ConversationMessage>> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(conversationMemoryService.getFullHistory(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Forget a session's conversation history (the next question in it starts fresh)")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable String sessionId) {
        conversationMemoryService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "cleared", true));
    }
}

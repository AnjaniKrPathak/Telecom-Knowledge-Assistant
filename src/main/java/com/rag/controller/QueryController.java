package com.rag.controller;

import com.rag.service.RagQueryService;
import com.rag.service.RagQueryService.RagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Tag(name = "RAG Query", description = "Ask questions against the knowledge base")
public class QueryController {

    private final RagQueryService ragQueryService;

    @PostMapping
    @Operation(summary = "Ask a question – returns answer + source references. " +
            "Pass back the \"sessionId\" from a previous response to continue that conversation " +
            "(follow-up questions are resolved using the conversation history); omit it to start a new one. " +
            "Every response also includes an \"interactionId\" — submit it to POST /api/feedback to rate the answer.")
    public ResponseEntity<RagResponse> query(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String sessionId = body.get("sessionId");
        RagResponse response = ragQueryService.query(question, sessionId);
        return ResponseEntity.ok(response);
    }
}

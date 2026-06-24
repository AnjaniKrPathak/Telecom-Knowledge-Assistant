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
    @Operation(summary = "Ask a question – returns answer + source references")
    public ResponseEntity<RagResponse> query(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        RagResponse response = ragQueryService.query(question);
        return ResponseEntity.ok(response);
    }
}

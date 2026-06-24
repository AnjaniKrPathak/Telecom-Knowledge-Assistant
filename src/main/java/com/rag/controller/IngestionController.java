package com.rag.controller;

import com.rag.model.DocumentRecord;
import com.rag.repository.DocumentRepository;
import com.rag.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document Ingestion", description = "Upload and ingest documents into the knowledge base")
public class IngestionController {

    private final IngestionService ingestionService;
    private final DocumentRepository documentRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a file (PDF, DOCX, XLSX/XLS, TXT)")
    public ResponseEntity<DocumentRecord> uploadFile(
            @RequestPart("file") MultipartFile file) {
        try {
            DocumentRecord record = ingestionService.ingestFile(file);
            return ResponseEntity.ok(record);
        } catch (IOException e) {
            log.error("Failed to ingest file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/url")
    @Operation(summary = "Ingest a web page by URL")
    public ResponseEntity<?> ingestUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }
        try {
            DocumentRecord record = ingestionService.ingestUrl(url);
            return ResponseEntity.ok(record);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to ingest URL: {}", url, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    @Operation(summary = "List all ingested documents")
    public ResponseEntity<List<DocumentRecord>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document record (does not remove embeddings)")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        documentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

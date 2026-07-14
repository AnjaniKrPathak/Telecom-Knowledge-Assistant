package com.rag.controller;

import com.rag.model.DocumentRecord;
import com.rag.model.IngestionBatch;
import com.rag.model.IngestionFailureRecord;
import com.rag.model.IngestionSuccessRecord;
import com.rag.model.dto.BatchReport;
import com.rag.repository.DocumentRepository;
import com.rag.repository.IngestionFailureRepository;
import com.rag.repository.IngestionSuccessRepository;
import com.rag.service.FolderIngestionService;
import com.rag.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
    private final FolderIngestionService folderIngestionService;
    private final IngestionSuccessRepository successRepository;
    private final IngestionFailureRepository failureRepository;

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

    // ── Folder upload (async batch ingestion) ────────────────────────────────

    @PostMapping("/upload-folder")
    @Operation(summary = "Upload all supported documents in a server-side folder. " +
            "Every file is ingested asynchronously; call GET /api/documents/batch/{batchId} " +
            "for the success/failure report once processing completes.")
    public ResponseEntity<?> uploadFolder(@RequestBody Map<String, String> body) {
        String folderPath = body.get("folderPath");
        if (folderPath == null || folderPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath is required"));
        }
        try {
            IngestionBatch batch = folderIngestionService.startFolderIngestion(folderPath);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "batchId", batch.getId(),
                    "folderPath", batch.getFolderPath(),
                    "totalFiles", batch.getTotalFiles(),
                    "status", batch.getStatus(),
                    "message", "Folder ingestion started. Poll GET /api/documents/batch/{batchId} for the report."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/batch/{batchId}")
    @Operation(summary = "Get the report for a folder ingestion batch: total / success / failure counts")
    public ResponseEntity<?> getBatchReport(@PathVariable String batchId) {
        try {
            BatchReport report = folderIngestionService.getReport(batchId);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/batch/{batchId}/success")
    @Operation(summary = "List the success queue entries for a folder ingestion batch")
    public ResponseEntity<List<IngestionSuccessRecord>> getBatchSuccesses(@PathVariable String batchId) {
        return ResponseEntity.ok(successRepository.findByBatchId(batchId));
    }

    @GetMapping("/batch/{batchId}/failures")
    @Operation(summary = "List the failure queue entries for a folder ingestion batch")
    public ResponseEntity<List<IngestionFailureRecord>> getBatchFailures(@PathVariable String batchId) {
        return ResponseEntity.ok(failureRepository.findByBatchId(batchId));
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

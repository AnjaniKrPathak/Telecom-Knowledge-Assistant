package com.rag.git.controller;

import com.rag.git.dto.DateReportResponse;
import com.rag.git.dto.IngestGitRequest;
import com.rag.git.model.GitRepositoryRecord;
import com.rag.git.repository.GitRepositoryJpaRepository;
import com.rag.git.service.CommitReportService;
import com.rag.git.service.GitIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
@Tag(name = "Git Ingestion", description = "Connect git repositories and report on commit history")
public class GitRepositoryController {

    private final GitIngestionService gitIngestionService;
    private final GitRepositoryJpaRepository gitRepositoryRepository;
    private final CommitReportService commitReportService;

    @PostMapping("/repositories")
    @Operation(summary = "Connect a git repository (clones it and ingests its full commit history asynchronously). " +
            "Poll GET /api/git/repositories/{id} for status.")
    public ResponseEntity<?> connect(@RequestBody IngestGitRequest request) {
        try {
            GitRepositoryRecord record = gitIngestionService.connect(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(record);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/repositories/{id}/sync")
    @Operation(summary = "Pull and ingest any new commits since the last sync")
    public ResponseEntity<?> sync(@PathVariable String id) {
        try {
            GitRepositoryRecord record = gitIngestionService.triggerSync(id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(record);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/repositories")
    @Operation(summary = "List all connected git repositories and their sync status")
    public ResponseEntity<List<GitRepositoryRecord>> list() {
        return ResponseEntity.ok(gitRepositoryRepository.findAll());
    }

    @GetMapping("/repositories/{id}")
    @Operation(summary = "Get one connected repository's status (CLONING / SYNCING / READY / FAILED)")
    public ResponseEntity<?> get(@PathVariable String id) {
        return gitRepositoryRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/repositories/{id}")
    @Operation(summary = "Stop tracking a repository (does not delete already-ingested commits or embeddings)")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        gitRepositoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Date-based commit report ─────────────────────────────────────────────
    @GetMapping("/commits/report")
    @Operation(summary = "Report of all commits on a date (or between two dates), optionally scoped to one repository")
    public ResponseEntity<?> dateReport(
            @RequestParam String date,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String repositoryId) {
        try {
            LocalDate from = LocalDate.parse(date);
            LocalDate to = toDate != null && !toDate.isBlank() ? LocalDate.parse(toDate) : null;
            DateReportResponse report = commitReportService.reportForDateRange(repositoryId, from, to);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

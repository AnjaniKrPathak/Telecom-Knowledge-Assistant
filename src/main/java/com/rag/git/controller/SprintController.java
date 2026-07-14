package com.rag.git.controller;

import com.rag.git.dto.CreateSprintRequest;
import com.rag.git.dto.GenerateSprintsRequest;
import com.rag.git.dto.SprintReportResponse;
import com.rag.git.model.Sprint;
import com.rag.git.service.CommitReportService;
import com.rag.git.service.SprintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sprints")
@RequiredArgsConstructor
@Tag(name = "Sprints", description = "Define sprints (manually or auto-generated) and report commit counts per sprint")
public class SprintController {

    private final SprintService sprintService;
    private final CommitReportService commitReportService;

    @PostMapping
    @Operation(summary = "Create one sprint manually (name + start/end date)")
    public ResponseEntity<?> create(@RequestBody CreateSprintRequest request) {
        try {
            return ResponseEntity.ok(sprintService.createManual(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate")
    @Operation(summary = "Auto-generate N consecutive fixed-length sprints starting from a date")
    public ResponseEntity<?> generate(@RequestBody GenerateSprintsRequest request) {
        try {
            return ResponseEntity.ok(sprintService.generate(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    @Operation(summary = "List all sprints")
    public ResponseEntity<List<Sprint>> list() {
        return ResponseEntity.ok(sprintService.listAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a sprint definition (does not touch commits)")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        sprintService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/report")
    @Operation(summary = "Commit count + author breakdown + commit list for one sprint")
    public ResponseEntity<?> report(@PathVariable String id,
                                     @RequestParam(required = false) String repositoryId) {
        try {
            SprintReportResponse report = commitReportService.reportForSprint(id, repositoryId);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/reports")
    @Operation(summary = "Commit count for every sprint on record — a sprint-over-sprint trend view")
    public ResponseEntity<?> allReports(@RequestParam(required = false) String repositoryId) {
        return ResponseEntity.ok(commitReportService.reportForAllSprints(repositoryId));
    }
}

package com.rag.controller;

import com.rag.model.dto.MetadataBackfillReport;
import com.rag.service.ExcelMetadataBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin/maintenance endpoints for Excel ingestion that operate on already-ingested chunks rather
 * than a fresh upload: re-tagging metadata that predates a feature, and surfacing what still
 * needs attention (unclassified sheets, flagged range warnings).
 */
@Slf4j
@RestController
@RequestMapping("/api/documents/excel")
@RequiredArgsConstructor
@Tag(name = "Excel Maintenance", description = "Backfill/re-tagging and diagnostics for already-ingested Excel chunks")
public class ExcelMaintenanceController {

    private final ExcelMetadataBackfillService backfillService;

    @PostMapping("/backfill-metadata")
    @Operation(summary = "Re-tag already-ingested Excel chunks with metadata added after they were " +
            "ingested: reconciles legacy priceKeyId-style keys onto their current canonical key, and " +
            "flags identifiers that fall outside their sheet's documented range. Rewrites stored " +
            "metadata in place — does not re-parse the workbook or re-embed anything. Safe to re-run.")
    public ResponseEntity<MetadataBackfillReport> backfillMetadata() {
        MetadataBackfillReport report = backfillService.runBackfill();
        return ResponseEntity.ok(report);
    }

    @GetMapping("/unclassified-sheets")
    @Operation(summary = "List already-ingested sheets that didn't match a known ExcelSheetType " +
            "pattern (classified as 'general'). Share each one's tab name and header row so a " +
            "dedicated pattern/aliases can be added for it.")
    public ResponseEntity<List<Map<String, Object>>> unclassifiedSheets() {
        return ResponseEntity.ok(backfillService.listUnclassifiedSheets());
    }

    @GetMapping("/range-warnings")
    @Operation(summary = "List already-ingested rows currently flagged with a range-validation " +
            "warning (an identifier whose value falls outside its sheet's documented range).")
    public ResponseEntity<List<Map<String, Object>>> rangeWarnings(
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(backfillService.listRangeWarnings(limit));
    }
}

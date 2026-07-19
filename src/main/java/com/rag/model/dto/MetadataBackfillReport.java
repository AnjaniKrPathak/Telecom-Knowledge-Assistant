package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary report for a run of {@code ExcelMetadataBackfillService} — the job that re-tags Excel
 * chunks that were embedded before the {@code priceKey}/{@code priceKeyId} alias and range-
 * validation-warning features existed, without needing to re-parse the source workbook or
 * re-embed anything (it rewrites each chunk's stored metadata JSON in place).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataBackfillReport {
    /** Excel chunks examined (every stored row where metadata->>'type' = 'EXCEL'). */
    private int rowsScanned;
    /** Chunks whose stored metadata was rewritten (either reconciliation, a range warning, or both). */
    private int rowsUpdated;
    /** Of {@link #rowsUpdated}, how many had a legacy key (e.g. {@code priceKeyId}) renamed onto its
     *  current canonical key (e.g. {@code priceKey}). */
    private int legacyKeysReconciled;
    /** Of {@link #rowsUpdated}, how many were newly flagged with a range-validation warning that
     *  wasn't already present. */
    private int rangeWarningsAdded;
    /** Chunks that could not be read/updated, capped for readability — see {@code errorSample}. */
    private int errorCount;
    /** Up to 20 "{@code <chunkId>: <message>}" entries for the first errors encountered. */
    private List<String> errorSample;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

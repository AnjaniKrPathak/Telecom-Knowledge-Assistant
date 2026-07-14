package com.rag.model.dto;

import com.rag.model.BatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Summary report returned for a folder ingestion batch:
 * total documents discovered, how many succeeded, how many failed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchReport {
    private String batchId;
    private String folderPath;
    private BatchStatus status;
    private int totalFiles;
    private int successCount;
    private int failureCount;
    private int pendingCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

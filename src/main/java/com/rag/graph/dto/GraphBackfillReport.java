package com.rag.graph.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Summary report for a {@code POST /api/graph/backfill} run — see {@code GraphBackfillService}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphBackfillReport {
    private boolean success;
    private String message;
    private int chunksScanned;
    private int entityMentionsWritten;
    private int relationshipsWritten;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents one "upload a folder" job.
 * Holds the final report: how many documents were discovered, how many
 * succeeded and how many failed, once all async ingestion tasks complete.
 */
@Entity
@Table(name = "ingestion_batches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 2048)
    private String folderPath;

    @Column(nullable = false)
    private int totalFiles;

    @Builder.Default
    @Column(nullable = false)
    private int successCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private int failureCount = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BatchStatus status = BatchStatus.IN_PROGRESS;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @PrePersist
    public void prePersist() {
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }
}

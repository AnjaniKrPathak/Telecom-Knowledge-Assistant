package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per document that failed ingestion within a batch.
 * Acts as the persisted "failure queue" for a folder upload job.
 */
@Entity
@Table(name = "ingestion_failure_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionFailureRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String batchId;

    @Column(nullable = false, length = 1024)
    private String fileName;

    @Column(length = 2048)
    private String filePath;

    @Column(length = 4000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime failedAt;

    @PrePersist
    public void prePersist() {
        this.failedAt = LocalDateTime.now();
    }
}

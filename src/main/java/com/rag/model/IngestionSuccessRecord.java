package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per successfully ingested document within a batch.
 * Acts as the persisted "success queue" for a folder upload job.
 */
@Entity
@Table(name = "ingestion_success_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionSuccessRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String batchId;

    @Column(nullable = false, length = 1024)
    private String fileName;

    @Column(length = 2048)
    private String filePath;

    private String documentRecordId;

    private int chunkCount;

    @Column(nullable = false)
    private LocalDateTime completedAt;

    @PrePersist
    public void prePersist() {
        this.completedAt = LocalDateTime.now();
    }
}

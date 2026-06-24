package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;   // PDF, DOCX, EXCEL, URL, TXT

    @Column(length = 2048)
    private String source; // file path or URL

    @Column(nullable = false)
    private int chunkCount;

    @Column(nullable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    public void prePersist() {
        this.ingestedAt = LocalDateTime.now();
    }
}

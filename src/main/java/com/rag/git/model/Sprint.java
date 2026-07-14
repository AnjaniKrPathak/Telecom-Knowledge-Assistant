package com.rag.git.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A named date range used to bucket and count commits ("Sprint 24", "2026-07-01 .. 2026-07-14").
 * Can be created manually or in bulk by the fixed-length auto-generator. Sprints are global by
 * default (spanning every tracked repository) unless {@code repositoryId} is set, in which case
 * the sprint only applies to that one repository's commit counts.
 */
@Entity
@Table(name = "sprints", indexes = {
        @Index(name = "idx_sprints_date_range", columnList = "startDate, endDate")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sprint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    /** Inclusive end date. */
    @Column(nullable = false)
    private LocalDate endDate;

    /** Null = applies across all repositories; set = scoped to one repository. */
    private String repositoryId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

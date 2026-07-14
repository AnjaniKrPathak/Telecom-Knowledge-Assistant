package com.rag.git.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One commit belonging to a tracked {@link GitRepositoryRecord}. Stores enough metadata to
 * answer "what happened on date X" and "how many commits in sprint Y" style reports without
 * re-reading the repository from disk.
 */
@Entity
@Table(name = "git_commits", indexes = {
        @Index(name = "idx_git_commits_repo_date", columnList = "repositoryId, commitDate"),
        @Index(name = "idx_git_commits_repo_hash", columnList = "repositoryId, commitHash", unique = true),
        @Index(name = "idx_git_commits_author", columnList = "authorEmail")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommitRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String repositoryId;

    @Column(nullable = false, length = 64)
    private String commitHash;

    @Column(nullable = false)
    private String authorName;

    @Column(nullable = false)
    private String authorEmail;

    /** The commit's committer timestamp (not author timestamp), used for all date/sprint reports. */
    @Column(nullable = false)
    private LocalDateTime commitDate;

    @Column(nullable = false, length = 500)
    private String shortMessage;

    @Column(length = 4000)
    private String fullMessage;

    @Column(nullable = false)
    private String branch;

    @Builder.Default
    private int filesChanged = 0;

    @Builder.Default
    private int insertions = 0;

    @Builder.Default
    private int deletions = 0;

    @Column(nullable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    public void prePersist() {
        if (this.ingestedAt == null) {
            this.ingestedAt = LocalDateTime.now();
        }
    }
}

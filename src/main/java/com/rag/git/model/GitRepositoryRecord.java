package com.rag.git.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One git repository the assistant has been pointed at. Tracks sync state so repeated syncs
 * only walk commits that haven't been ingested yet.
 */
@Entity
@Table(name = "git_repositories", indexes = {
        @Index(name = "idx_git_repositories_repo_url", columnList = "repoUrl", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitRepositoryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 2048)
    private String repoUrl;

    /** Short display name derived from the URL, e.g. "org/repo". */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String branch;

    /** Credentials for private repos (HTTPS username + personal access token). Never exposed via API. */
    @JsonIgnore
    private String credentialUsername;

    @JsonIgnore
    @Column(length = 1024)
    private String credentialToken;

    /** Local clone path on disk. */
    @Column(length = 2048)
    private String localPath;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GitRepoStatus status = GitRepoStatus.CLONING;

    @Builder.Default
    @Column(nullable = false)
    private int totalCommits = 0;

    /** SHA of the newest commit ingested so far, used to stop early on incremental syncs. */
    private String lastSyncedCommitHash;

    private LocalDateTime lastSyncedAt;

    @Column(length = 2048)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

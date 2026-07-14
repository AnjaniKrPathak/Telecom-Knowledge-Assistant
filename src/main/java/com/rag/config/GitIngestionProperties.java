package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.git.*" properties from application.yml.
 *
 * <pre>
 * rag:
 *   git:
 *     workspace-dir: ./git-workspace   # where repos are cloned to on local disk
 *     compute-diff-stats: true         # compute insertions/deletions/files-changed per commit
 *     embed-commits: true              # index commit messages into the vector store for RAG chat
 *     max-commits-per-sync: 20000      # safety cap per ingest/sync call
 *     default-sprint-length-days: 14   # used by the fixed-length sprint auto-generator
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "rag.git")
@Data
public class GitIngestionProperties {

    /** Local directory repositories are cloned into (one subfolder per repository id). */
    private String workspaceDir = "./git-workspace";

    /**
     * Whether to compute per-commit insertions/deletions/files-changed via a tree diff.
     * Adds real cost on large repos/histories; turn off if you only need commit metadata.
     */
    private boolean computeDiffStats = true;

    /** Whether commit messages are chunked and embedded into the vector store for RAG chat. */
    private boolean embedCommits = true;

    /** Safety cap on how many commits a single ingest/sync call will walk and persist. */
    private int maxCommitsPerSync = 20_000;

    /** Sprint length (in days) used by the fixed-length sprint auto-generator. */
    private int defaultSprintLengthDays = 14;
}

package com.rag.git.service;

import com.rag.config.GitIngestionProperties;
import com.rag.git.dto.IngestGitRequest;
import com.rag.git.model.CommitRecord;
import com.rag.git.model.GitRepoStatus;
import com.rag.git.model.GitRepositoryRecord;
import com.rag.git.repository.CommitJpaRepository;
import com.rag.git.repository.GitRepositoryJpaRepository;
import com.rag.service.IngestionService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects the assistant to a git repository, walks its commit history, and stores structured
 * commit metadata (author, date, message, files/insertions/deletions) so it can be reported on
 * by date or by sprint. Optionally also embeds each commit message into the same pgvector store
 * used for documents, so commit history becomes searchable through the normal RAG chat.
 * <p>
 * Sync is incremental: after the first clone, subsequent calls do a {@code git pull} and walk
 * commits from HEAD only until they hit one already stored for this repository (linear-history
 * assumption — good enough for typical single-branch tracking; force-pushed/rewritten history
 * is not specially handled).
 */
@Slf4j
@Service
public class GitIngestionService {

    private final GitRepositoryJpaRepository repoRepository;
    private final CommitJpaRepository commitRepository;
    private final GitIngestionProperties properties;
    private final IngestionService ingestionService;
    private final GitIngestionService self;

    public GitIngestionService(GitRepositoryJpaRepository repoRepository,
                                CommitJpaRepository commitRepository,
                                GitIngestionProperties properties,
                                IngestionService ingestionService,
                                @Lazy GitIngestionService self) {
        this.repoRepository = repoRepository;
        this.commitRepository = commitRepository;
        this.properties = properties;
        this.ingestionService = ingestionService;
        this.self = self;
    }

    // ── Connect a brand-new repository ───────────────────────────────────────
    public GitRepositoryRecord connect(IngestGitRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (repoRepository.existsByRepoUrl(request.repoUrl())) {
            throw new IllegalArgumentException(
                    "Repository already connected: " + request.repoUrl() + ". Use the sync endpoint to pull new commits.");
        }

        GitRepositoryRecord record = GitRepositoryRecord.builder()
                .repoUrl(request.repoUrl())
                .name(deriveName(request.repoUrl()))
                .branch(nullToEmpty(request.branch()))
                .credentialUsername(request.username())
                .credentialToken(request.token())
                .status(GitRepoStatus.CLONING)
                .build();
        record = repoRepository.save(record);

        self.syncAsync(record.getId());
        return record;
    }

    // ── Re-sync an already-connected repository (pulls only new commits) ────
    public GitRepositoryRecord triggerSync(String repositoryId) {
        GitRepositoryRecord record = repoRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
        record.setStatus(GitRepoStatus.SYNCING);
        repoRepository.save(record);
        self.syncAsync(repositoryId);
        return record;
    }

    @Async("gitTaskExecutor")
    public void syncAsync(String repositoryId) {
        GitRepositoryRecord record = repoRepository.findById(repositoryId).orElse(null);
        if (record == null) {
            log.error("Git repository {} disappeared before sync could run", repositoryId);
            return;
        }
        try {
            doSync(record);
        } catch (Exception e) {
            log.error("Git sync failed for '{}': {}", record.getRepoUrl(), e.getMessage(), e);
            record.setStatus(GitRepoStatus.FAILED);
            record.setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            repoRepository.save(record);
        }
    }

    // ── Core sync logic ──────────────────────────────────────────────────────
    private void doSync(GitRepositoryRecord record) throws Exception {
        Path workspaceRoot = Path.of(properties.getWorkspaceDir());
        Files.createDirectories(workspaceRoot);
        Path workspace = workspaceRoot.resolve(record.getId());

        CredentialsProvider credentials = hasCredentials(record)
                ? new UsernamePasswordCredentialsProvider(record.getCredentialUsername(), record.getCredentialToken())
                : null;

        boolean freshClone = record.getLocalPath() == null
                || !Files.exists(Path.of(record.getLocalPath()).resolve(".git"));

        try (Git git = freshClone ? cloneRepo(record, workspace, credentials) : openAndPull(record, credentials)) {
            Repository repository = git.getRepository();
            String branch = record.getBranch() == null || record.getBranch().isBlank()
                    ? repository.getBranch()
                    : record.getBranch();

            record.setLocalPath(workspace.toString());
            record.setBranch(branch);

            List<CommitRecord> newCommits = walkNewCommits(record, repository, branch);
            if (!newCommits.isEmpty()) {
                commitRepository.saveAll(newCommits);
                record.setLastSyncedCommitHash(newCommits.get(0).getCommitHash()); // newest-first
            }

            record.setTotalCommits((int) commitRepository.countByRepositoryId(record.getId()));
            record.setStatus(GitRepoStatus.READY);
            record.setLastSyncedAt(LocalDateTime.now());
            record.setLastError(null);
            repoRepository.save(record);

            if (properties.isEmbedCommits() && !newCommits.isEmpty()) {
                embedCommits(record, newCommits);
            }

            log.info("Git sync complete for '{}': {} new commit(s), {} total",
                    record.getRepoUrl(), newCommits.size(), record.getTotalCommits());
        }
    }

    private Git cloneRepo(GitRepositoryRecord record, Path workspace, CredentialsProvider credentials)
            throws org.eclipse.jgit.api.errors.GitAPIException {
        log.info("Cloning '{}' into {}", record.getRepoUrl(), workspace);
        CloneCommand clone = Git.cloneRepository()
                .setURI(record.getRepoUrl())
                .setDirectory(workspace.toFile())
                .setCloneAllBranches(true);
        if (record.getBranch() != null && !record.getBranch().isBlank()) {
            clone.setBranchesToClone(List.of("refs/heads/" + record.getBranch()));
            clone.setBranch(record.getBranch());
        }
        if (credentials != null) {
            clone.setCredentialsProvider(credentials);
        }
        return clone.call();
    }

    private Git openAndPull(GitRepositoryRecord record, CredentialsProvider credentials)
            throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        Git git = Git.open(Path.of(record.getLocalPath()).toFile());
        PullCommand pull = git.pull();
        if (credentials != null) {
            pull.setCredentialsProvider(credentials);
        }
        pull.call();
        return git;
    }

    private List<CommitRecord> walkNewCommits(GitRepositoryRecord record, Repository repository, String branch)
            throws IOException {
        List<CommitRecord> result = new ArrayList<>();

        ObjectId head = repository.resolve("refs/heads/" + branch);
        if (head == null) {
            head = repository.resolve("HEAD");
        }
        if (head == null) {
            log.warn("Could not resolve HEAD for '{}' (branch '{}')", record.getRepoUrl(), branch);
            return result;
        }

        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.markStart(revWalk.parseCommit(head));
            int processed = 0;
            for (RevCommit commit : revWalk) {
                if (processed++ >= properties.getMaxCommitsPerSync()) {
                    log.warn("Reached max-commits-per-sync ({}) for '{}', stopping early",
                            properties.getMaxCommitsPerSync(), record.getRepoUrl());
                    break;
                }

                String hash = commit.getName();
                if (commitRepository.existsByRepositoryIdAndCommitHash(record.getId(), hash)) {
                    // Linear-history assumption: once we reach a commit we already have,
                    // everything reachable further back was ingested by a previous sync.
                    break;
                }

                GitCommitStatsExtractor.Stats stats = properties.isComputeDiffStats()
                        ? GitCommitStatsExtractor.compute(repository, revWalk, commit)
                        : GitCommitStatsExtractor.Stats.EMPTY;

                String shortMessage = commit.getShortMessage() == null ? "" : commit.getShortMessage();
                String fullMessage = commit.getFullMessage() == null ? "" : commit.getFullMessage().trim();

                result.add(CommitRecord.builder()
                        .repositoryId(record.getId())
                        .commitHash(hash)
                        .authorName(commit.getAuthorIdent().getName())
                        .authorEmail(commit.getAuthorIdent().getEmailAddress())
                        .commitDate(toLocalDateTime(commit.getCommitterIdent()))
                        .shortMessage(truncate(shortMessage, 500))
                        .fullMessage(truncate(fullMessage, 4000))
                        .branch(branch)
                        .filesChanged(stats.filesChanged())
                        .insertions(stats.insertions())
                        .deletions(stats.deletions())
                        .build());
            }
        }
        return result;
    }

    // ── Embed commit messages into the vector store for RAG chat ────────────
    private void embedCommits(GitRepositoryRecord record, List<CommitRecord> commits) {
        List<TextSegment> segments = commits.stream()
                .map(c -> toSegment(record, c))
                .toList();
        ingestionService.ingestSegments(segments, record.getRepoUrl(), "GIT_COMMIT");
    }

    private TextSegment toSegment(GitRepositoryRecord record, CommitRecord c) {
        String message = (c.getFullMessage() == null || c.getFullMessage().isBlank())
                ? c.getShortMessage() : c.getFullMessage();

        String text = """
                Repository: %s
                Branch: %s
                Commit: %s
                Author: %s <%s>
                Date: %s
                Message: %s
                Files changed: %d (+%d / -%d)
                """.formatted(
                record.getName(), c.getBranch(), shortHash(c.getCommitHash()),
                c.getAuthorName(), c.getAuthorEmail(), c.getCommitDate(),
                message, c.getFilesChanged(), c.getInsertions(), c.getDeletions());

        Metadata metadata = Metadata.from("source", record.getRepoUrl())
                .put("type", "GIT_COMMIT")
                .put("repository", record.getName())
                .put("commitHash", c.getCommitHash())
                .put("author", c.getAuthorName())
                .put("commitDate", c.getCommitDate().toString());

        return TextSegment.from(text.trim(), metadata);
    }

    // ── Small helpers ─────────────────────────────────────────────────────────
    private boolean hasCredentials(GitRepositoryRecord record) {
        return record.getCredentialUsername() != null && !record.getCredentialUsername().isBlank();
    }

    private LocalDateTime toLocalDateTime(PersonIdent ident) {
        return LocalDateTime.ofInstant(ident.getWhen().toInstant(), ident.getTimeZone().toZoneId());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String shortHash(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String deriveName(String repoUrl) {
        String cleaned = repoUrl.replaceAll("\\.git$", "").replaceAll("/+$", "");
        String[] parts = cleaned.split("[/:]");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }
        return cleaned;
    }
}

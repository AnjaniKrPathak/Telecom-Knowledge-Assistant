package com.rag.git.service;

import com.rag.git.dto.AuthorCommitCount;
import com.rag.git.dto.CommitDto;
import com.rag.git.dto.DateReportResponse;
import com.rag.git.dto.SprintReportResponse;
import com.rag.git.model.CommitRecord;
import com.rag.git.model.Sprint;
import com.rag.git.repository.CommitJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Turns raw {@link CommitRecord} rows into the two report shapes the assistant exposes:
 * a date/range report ("what commits happened on/between these dates") and a sprint report
 * ("how many commits, by whom, in this sprint").
 */
@Service
@RequiredArgsConstructor
public class CommitReportService {

    private final CommitJpaRepository commitRepository;
    private final SprintService sprintService;

    // ── Report for a single date or a date range ─────────────────────────────
    public DateReportResponse reportForDateRange(String repositoryId, LocalDate from, LocalDate to) {
        if (from == null) {
            throw new IllegalArgumentException("from date is required");
        }
        LocalDate effectiveTo = to != null ? to : from;
        if (effectiveTo.isBefore(from)) {
            throw new IllegalArgumentException("to date must not be before from date");
        }

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = effectiveTo.atTime(LocalTime.MAX);

        List<CommitRecord> commits = repositoryId != null && !repositoryId.isBlank()
                ? commitRepository.findByRepositoryIdAndCommitDateBetweenOrderByCommitDateDesc(repositoryId, start, end)
                : commitRepository.findByCommitDateBetweenOrderByCommitDateDesc(start, end);

        List<AuthorCommitCount> byAuthor = authorBreakdown(repositoryId, start, end);

        return new DateReportResponse(
                repositoryId,
                from,
                effectiveTo,
                commits.size(),
                byAuthor,
                commits.stream().map(CommitDto::from).toList()
        );
    }

    // ── Report for a sprint ───────────────────────────────────────────────────
    public SprintReportResponse reportForSprint(String sprintId, String repositoryIdOverride) {
        Sprint sprint = sprintService.get(sprintId);

        // A repositoryId passed explicitly to the report call narrows a global sprint to one
        // repository; a sprint that was itself created scoped to a repository always wins.
        String effectiveRepositoryId = sprint.getRepositoryId() != null ? sprint.getRepositoryId() : repositoryIdOverride;

        LocalDateTime start = sprint.getStartDate().atStartOfDay();
        LocalDateTime end = sprint.getEndDate().atTime(LocalTime.MAX);

        List<CommitRecord> commits = effectiveRepositoryId != null && !effectiveRepositoryId.isBlank()
                ? commitRepository.findByRepositoryIdAndCommitDateBetweenOrderByCommitDateDesc(effectiveRepositoryId, start, end)
                : commitRepository.findByCommitDateBetweenOrderByCommitDateDesc(start, end);

        List<AuthorCommitCount> byAuthor = authorBreakdown(effectiveRepositoryId, start, end);

        return new SprintReportResponse(
                sprint.getId(),
                sprint.getName(),
                sprint.getStartDate(),
                sprint.getEndDate(),
                effectiveRepositoryId,
                commits.size(),
                byAuthor,
                commits.stream().map(CommitDto::from).toList()
        );
    }

    /** Commit counts for every sprint on record, useful for a sprint-over-sprint trend view. */
    public List<SprintReportResponse> reportForAllSprints(String repositoryIdOverride) {
        return sprintService.listAll().stream()
                .map(sprint -> reportForSprint(sprint.getId(), repositoryIdOverride))
                .toList();
    }

    private List<AuthorCommitCount> authorBreakdown(String repositoryId, LocalDateTime start, LocalDateTime end) {
        List<Object[]> rows = repositoryId != null && !repositoryId.isBlank()
                ? commitRepository.countByAuthorBetweenForRepository(repositoryId, start, end)
                : commitRepository.countByAuthorBetween(start, end);

        return rows.stream()
                .map(row -> new AuthorCommitCount((String) row[0], (String) row[1], (Long) row[2]))
                .toList();
    }
}

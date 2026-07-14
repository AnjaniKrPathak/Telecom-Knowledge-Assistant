package com.rag.git.dto;

import java.time.LocalDate;
import java.util.List;

public record SprintReportResponse(
        String sprintId,
        String sprintName,
        LocalDate startDate,
        LocalDate endDate,
        String repositoryId,
        long totalCommits,
        List<AuthorCommitCount> byAuthor,
        List<CommitDto> commits
) {
}

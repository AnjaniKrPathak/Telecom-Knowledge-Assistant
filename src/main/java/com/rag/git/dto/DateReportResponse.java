package com.rag.git.dto;

import java.time.LocalDate;
import java.util.List;

public record DateReportResponse(
        String repositoryId,
        LocalDate from,
        LocalDate to,
        long totalCommits,
        List<AuthorCommitCount> byAuthor,
        List<CommitDto> commits
) {
}

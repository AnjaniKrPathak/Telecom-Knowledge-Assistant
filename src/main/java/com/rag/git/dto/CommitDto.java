package com.rag.git.dto;

import com.rag.git.model.CommitRecord;

import java.time.LocalDateTime;

public record CommitDto(
        String repositoryId,
        String commitHash,
        String authorName,
        String authorEmail,
        LocalDateTime commitDate,
        String shortMessage,
        String branch,
        int filesChanged,
        int insertions,
        int deletions
) {
    public static CommitDto from(CommitRecord record) {
        return new CommitDto(
                record.getRepositoryId(),
                record.getCommitHash(),
                record.getAuthorName(),
                record.getAuthorEmail(),
                record.getCommitDate(),
                record.getShortMessage(),
                record.getBranch(),
                record.getFilesChanged(),
                record.getInsertions(),
                record.getDeletions()
        );
    }
}

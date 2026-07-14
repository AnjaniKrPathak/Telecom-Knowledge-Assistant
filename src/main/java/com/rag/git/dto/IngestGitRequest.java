package com.rag.git.dto;

/**
 * @param repoUrl  HTTPS clone URL, e.g. https://github.com/org/repo.git
 * @param branch   branch to track; defaults to the repo's default branch when null/blank
 * @param username git username (required only for private repos)
 * @param token    personal access token used as the HTTPS password (required only for private repos)
 */
public record IngestGitRequest(String repoUrl, String branch, String username, String token) {
}

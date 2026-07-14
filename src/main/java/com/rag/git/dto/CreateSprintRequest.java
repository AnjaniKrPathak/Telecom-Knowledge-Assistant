package com.rag.git.dto;

import java.time.LocalDate;

/**
 * @param repositoryId optional; when null the sprint spans every tracked repository
 */
public record CreateSprintRequest(String name, LocalDate startDate, LocalDate endDate, String repositoryId) {
}

package com.rag.git.dto;

import java.time.LocalDate;

/**
 * @param startDate        first sprint's start date
 * @param sprintLengthDays length of each sprint in days; falls back to rag.git.default-sprint-length-days when null
 * @param numberOfSprints  how many consecutive sprints to generate
 * @param namePrefix       e.g. "Sprint" -> "Sprint 1", "Sprint 2", ...; defaults to "Sprint"
 * @param repositoryId     optional; when null the generated sprints span every tracked repository
 */
public record GenerateSprintsRequest(
        LocalDate startDate,
        Integer sprintLengthDays,
        int numberOfSprints,
        String namePrefix,
        String repositoryId
) {
}

package com.rag.git.service;

import com.rag.config.GitIngestionProperties;
import com.rag.git.dto.CreateSprintRequest;
import com.rag.git.dto.GenerateSprintsRequest;
import com.rag.git.model.Sprint;
import com.rag.git.repository.SprintJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages sprints used to bucket commits for counting. Supports both manually-defined sprints
 * (arbitrary name + date range) and bulk generation of consecutive fixed-length sprints from a
 * start date — e.g. 14-day sprints starting today, ten of them in a row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SprintService {

    private final SprintJpaRepository sprintRepository;
    private final GitIngestionProperties properties;

    public Sprint createManual(CreateSprintRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        Sprint sprint = Sprint.builder()
                .name(request.name())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .repositoryId(request.repositoryId())
                .build();
        return sprintRepository.save(sprint);
    }

    /** Generates N consecutive, non-overlapping fixed-length sprints starting at {@code startDate}. */
    public List<Sprint> generate(GenerateSprintsRequest request) {
        if (request.startDate() == null) {
            throw new IllegalArgumentException("startDate is required");
        }
        if (request.numberOfSprints() <= 0) {
            throw new IllegalArgumentException("numberOfSprints must be greater than 0");
        }

        int lengthDays = request.sprintLengthDays() != null && request.sprintLengthDays() > 0
                ? request.sprintLengthDays()
                : properties.getDefaultSprintLengthDays();
        String prefix = request.namePrefix() == null || request.namePrefix().isBlank()
                ? "Sprint" : request.namePrefix();

        List<Sprint> sprints = new ArrayList<>();
        LocalDate cursor = request.startDate();
        for (int i = 1; i <= request.numberOfSprints(); i++) {
            LocalDate end = cursor.plusDays(lengthDays - 1L); // inclusive end date
            sprints.add(Sprint.builder()
                    .name(prefix + " " + i)
                    .startDate(cursor)
                    .endDate(end)
                    .repositoryId(request.repositoryId())
                    .build());
            cursor = end.plusDays(1);
        }

        List<Sprint> saved = sprintRepository.saveAll(sprints);
        log.info("Generated {} fixed-length sprint(s) of {} day(s) starting {}",
                saved.size(), lengthDays, request.startDate());
        return saved;
    }

    public List<Sprint> listAll() {
        return sprintRepository.findAllByOrderByStartDateDesc();
    }

    public Sprint get(String sprintId) {
        return sprintRepository.findById(sprintId)
                .orElseThrow(() -> new IllegalArgumentException("Sprint not found: " + sprintId));
    }

    public void delete(String sprintId) {
        sprintRepository.deleteById(sprintId);
    }
}

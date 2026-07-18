package com.rag.search;

import com.rag.config.QueryRoutingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Looks at the raw question text and decides whether it's clearly about structured spreadsheet
 * data ("Flat Offering", "Offering ID", "External ID", "TUTI", ...) or narrative documentation
 * ("What is...", "How does...", "Explain..."), so RagQueryService can narrow retrieval to just
 * that document type (metadata {@code type} = EXCEL or DOCX) before running the vector search —
 * cutting out irrelevant cross-type noise and making retrieval both faster and more precise.
 * <p>
 * Deliberately simple, fast keyword matching rather than an LLM call — this has to run on every
 * question before retrieval even starts. If it's unsure (no keyword match), or a filtered search
 * comes back empty, RagQueryService falls back to searching across all types, so a
 * misclassification never causes a "no information found" dead end — see rag.query-routing.*.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTypeClassifier {

    private final QueryRoutingProperties properties;

    /** Returns the metadata "type" value to filter retrieval by (e.g. "EXCEL", "DOCX"), or empty if the question doesn't clearly point at one. */
    public Optional<String> classify(String question) {
        if (!properties.isEnabled() || question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = question.toLowerCase();

        if (matchesAny(normalized, properties.getExcelKeywords())) {
            log.debug("Query routing: '{}' matched an EXCEL keyword — filtering retrieval to type=EXCEL", question);
            return Optional.of("EXCEL");
        }
        if (matchesAny(normalized, properties.getDocxKeywords())) {
            log.debug("Query routing: '{}' matched a DOCX keyword — filtering retrieval to type=DOCX", question);
            return Optional.of("DOCX");
        }
        return Optional.empty();
    }

    private boolean matchesAny(String normalizedQuestion, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .anyMatch(keyword -> normalizedQuestion.contains(keyword.toLowerCase().trim()));
    }
}

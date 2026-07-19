package com.rag.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Classifies an incoming question as {@link QueryIntent#LOOKUP} (wants a specific record/attribute
 * out of the Excel catalog — Offering Id, External ID, Change Request ID, Bundle ID/Name,
 * Discount ID/Name, Rule ID/Name, Tariff Name) or {@link QueryIntent#EXPLANATION} (wants a
 * definition or "how does X work" answer out of the narrative DOCX corpus — FDS, BRD, business
 * rules).
 * <p>
 * A naive "does the question contain the substring 'offering' or 'id'" regex over-triggers: it
 * would misclassify "What is Flat Offering?" (a definition question) as a lookup, just because
 * the word "offering" appears. This service instead layers three checks, each higher-precision
 * than the last:
 * <ol>
 *   <li><b>Explicit lookup phrases</b> — "flat offering id"/"offering id"/"flat offering", "external id",
 *       "change request id"/"cr id", "bundle id", "discount id", "rule id", "tariff name",
 *       "price key", "offering template", "design task", "tuti", "offering name", ... — checked
 *       first because these are unambiguous even when the question also contains "what is"
 *       (e.g. "What is the Offering Id for CR-1029?").</li>
 *   <li><b>Explanation phrasing</b> — "what is", "explain", "describe", "how does/do/is", "why",
 *       "define", "tell me about" — checked next, so plain definition questions are not swept up
 *       by a bare "id"/"offering" match later.</li>
 *   <li><b>Bare identifier/code signals</b> — a lone "id" token, or an alphanumeric code that
 *       looks like a record identifier (e.g. "CR-1029", "FO1234") — checked last as a fallback for
 *       terse queries like "ID for CR-1029?" that don't use any of the above phrasing.</li>
 * </ol>
 * Anything matching none of the above defaults to {@link QueryIntent#EXPLANATION}, since that's
 * the safer default for open-ended questions.
 */
@Slf4j
@Service
public class IntentDetectionService {

    private static final Pattern LOOKUP_PHRASES = Pattern.compile(
            "\\b(offering name|flat offering id|flat offering|offering id|external id|cr id|change request id|" +
            "product id|service id|bundle id|bundle name|discount id|discount name|" +
            "rule id|rule name|relation id|tariff name|price key|price list item|" +
            "offering template|design task|tuti)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLANATION_PHRASING = Pattern.compile(
            "^\\s*(what is|what are|what's|explain|describe|how does|how do|how is|how are|why does|why is|why do|define|tell me about)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_ID_TOKEN = Pattern.compile("\\bid\\b", Pattern.CASE_INSENSITIVE);

    /** e.g. "CR-1029", "FO1234", "SVC_007" — a short alpha prefix glued to a run of digits. */
    private static final Pattern IDENTIFIER_CODE = Pattern.compile("\\b[A-Za-z]{1,6}[-_]?\\d{2,}\\b");

    public QueryIntent detect(String question) {
        if (question == null || question.isBlank()) {
            return QueryIntent.EXPLANATION;
        }
        String q = question.trim();

        if (LOOKUP_PHRASES.matcher(q).find()) {
            log.debug("Intent=LOOKUP (explicit lookup phrase) for question: {}", question);
            return QueryIntent.LOOKUP;
        }
        if (EXPLANATION_PHRASING.matcher(q).find()) {
            log.debug("Intent=EXPLANATION (definition/how-does phrasing) for question: {}", question);
            return QueryIntent.EXPLANATION;
        }
        if (BARE_ID_TOKEN.matcher(q).find() || IDENTIFIER_CODE.matcher(q).find()) {
            log.debug("Intent=LOOKUP (bare id/identifier-code fallback) for question: {}", question);
            return QueryIntent.LOOKUP;
        }
        log.debug("Intent=EXPLANATION (default) for question: {}", question);
        return QueryIntent.EXPLANATION;
    }
}

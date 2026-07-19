package com.rag.graph.extraction;

import com.rag.document.excel.ExcelCatalogFields;
import com.rag.graph.model.GraphEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chunk's structured catalog fields (from {@link com.rag.document.excel.ExcelCatalogFields})
 * and, as a fallback, its raw narrative text into {@link GraphEntity} candidates for the knowledge
 * graph.
 * <p>
 * Two extraction paths, used together:
 * <ol>
 *   <li>{@link #extractFromMetadata} — the primary, high-precision path. Excel-derived chunks
 *       already carry precise business-identifier fields (offeringName, flatOfferingId,
 *       changeRequestId, ruleId, discountId, bundleId, tariffName, relationId, priceKey, plus any
 *       ops-configured or dynamically-detected *Id/*Name/*Key/*Code column) — every one of these
 *       becomes an entity, with its type derived by {@link EntityTypeResolver}.</li>
 *   <li>{@link #extractFromText} — a best-effort fallback for narrative (DOCX/PDF) chunks that
 *       don't carry structured metadata, using a small set of regex patterns for common telecom
 *       identifier shapes (CR-1029, Rule 71325001, Discount D-4521, ...). This is intentionally
 *       conservative (few patterns, anchored on a keyword) to avoid flooding the graph with false
 *       positives from ordinary numbers in prose.</li>
 * </ol>
 */
@Slf4j
@Component
public class EntityExtractionService {

    /** Metadata keys that are derived/structural annotations, not entities in their own right —
     *  never extracted even though they pass {@link com.rag.document.excel.ExcelCatalogFields#extract}'s
     *  own structural-key filter (which only excludes the base chunk-location keys). */
    private static final Set<String> NON_ENTITY_KEYS = Set.of(
            ExcelCatalogFields.TAB_HEADER,
            ExcelCatalogFields.RANGE_WARNINGS_KEY,
            ExcelCatalogFields.HAS_RANGE_WARNING,
            ExcelCatalogFields.ID_RANGE_NOTE);

    private static final String RANGE_START_SUFFIX = ExcelCatalogFields.RANGE_START_SUFFIX;
    private static final String RANGE_END_SUFFIX = ExcelCatalogFields.RANGE_END_SUFFIX;
    private static final String RANGE_WARNING_SUFFIX = ExcelCatalogFields.RANGE_WARNING_SUFFIX;

    /**
     * Builds one entity per usable catalog field on the chunk.
     *
     * @param catalogFields non-structural metadata fields already extracted for this chunk (see
     *                       {@code ExcelCatalogFields#extract}/{@code #extractFromRaw})
     * @param source         the chunk's source (document/workbook path or URL)
     */
    public List<GraphEntity> extractFromMetadata(Map<String, String> catalogFields, String source) {
        List<GraphEntity> entities = new ArrayList<>();
        if (catalogFields == null || catalogFields.isEmpty()) {
            return entities;
        }
        for (Map.Entry<String, String> entry : catalogFields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!isEntityWorthy(key, value)) {
                continue;
            }
            String type = EntityTypeResolver.resolveType(key);
            String normalizedValue = value.trim();
            entities.add(new GraphEntity(buildId(type, normalizedValue), type, normalizedValue, source));
        }
        return entities;
    }

    private boolean isEntityWorthy(String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (NON_ENTITY_KEYS.contains(key)) {
            return false;
        }
        // Skip *RangeStart / *RangeEnd / *RangeWarning annotation keys (sheet-documented ID ranges
        // and their violations) — these describe a field, they aren't identifiers themselves.
        if (key.endsWith(RANGE_START_SUFFIX) || key.endsWith(RANGE_END_SUFFIX) || key.endsWith(RANGE_WARNING_SUFFIX)) {
            return false;
        }
        // A bare "true"/"false" flag (e.g. hasRangeWarning slipping through some other path) isn't
        // a meaningful graph entity value.
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return false;
        }
        return true;
    }

    // ── Narrative-text fallback ──────────────────────────────────────────────
    /**
     * keyword-anchored pattern -> canonical entity type. Each pattern requires the keyword itself
     * plus a following alphanumeric/dash token that looks like an identifier — this keeps the
     * fallback conservative rather than matching any bare number in prose.
     */
    private static final Map<Pattern, String> NARRATIVE_PATTERNS = buildNarrativePatterns();

    private static Map<Pattern, String> buildNarrativePatterns() {
        Map<Pattern, String> patterns = new LinkedHashMap<>();
        patterns.put(Pattern.compile("\\bCR[-\\s]?(\\d{3,})\\b", Pattern.CASE_INSENSITIVE), "ChangeRequest");
        patterns.put(Pattern.compile("\\bChange\\s+Request\\s*[:#]?\\s*(\\d{3,})\\b", Pattern.CASE_INSENSITIVE), "ChangeRequest");
        patterns.put(Pattern.compile("\\bRule\\s*(?:ID|Id)?\\s*[:#]?\\s*(\\d{4,})\\b"), "Rule");
        patterns.put(Pattern.compile("\\bDiscount\\s*(?:ID|Id)?\\s*[:#]?\\s*([A-Za-z]?-?\\d{3,})\\b"), "Discount");
        patterns.put(Pattern.compile("\\bBundle\\s*(?:ID|Id)?\\s*[:#]?\\s*(\\d{3,})\\b"), "Bundle");
        patterns.put(Pattern.compile("\\bFlat\\s+Offering\\s*(?:ID|Id)?\\s*[:#]?\\s*(\\d{4,})\\b", Pattern.CASE_INSENSITIVE), "FlatOffering");
        patterns.put(Pattern.compile("\\bOffering\\s+ID\\s*[:#]?\\s*(\\d{4,})\\b", Pattern.CASE_INSENSITIVE), "FlatOffering");
        patterns.put(Pattern.compile("\\bExternal\\s+ID\\s*[:#]?\\s*([A-Za-z0-9-]{3,})\\b", Pattern.CASE_INSENSITIVE), "External");
        return patterns;
    }

    /**
     * Best-effort identifier extraction from free text (DOCX/PDF narrative chunks — FDS, BRD,
     * rules documents). Deliberately conservative: only fires on a recognized keyword immediately
     * followed by an identifier-shaped token, so ordinary prose numbers aren't turned into nodes.
     */
    public List<GraphEntity> extractFromText(String text, String source) {
        List<GraphEntity> entities = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return entities;
        }
        for (Map.Entry<Pattern, String> entry : NARRATIVE_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(text);
            String type = entry.getValue();
            while (matcher.find()) {
                String value = matcher.group(1).trim();
                if (value.isEmpty()) {
                    continue;
                }
                entities.add(new GraphEntity(buildId(type, value), type, value, source));
            }
        }
        return entities;
    }

    /** Stable, dedup-safe entity id: type + normalized value (lowercased, whitespace-collapsed). */
    public static String buildId(String type, String value) {
        String normalized = value.trim().toLowerCase().replaceAll("\\s+", " ");
        return type + ":" + normalized;
    }
}

package com.rag.document.excel;

import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves which columns in an Excel sheet's header row are business identifiers worth stamping
 * onto chunk metadata (so a chunk can be located/filtered by "Flat Offering ID" or "CR ID" rather
 * than only by free-text similarity), via two complementary mechanisms:
 * <ol>
 *   <li><b>Known aliases ({@link #matchHeaders}).</b> A small hand-maintained synonym table for
 *       identifiers whose column header doesn't already spell out the desired canonical name —
 *       e.g. a sheet's "Offering Id" column and another sheet's "Flat Offering" column both mean
 *       the same business concept, {@code flatOfferingId}, but neither header text is that name.
 *       This table only needs an entry when the header text and the desired canonical name
 *       genuinely differ in wording.</li>
 *   <li><b>Generic fallback ({@link #matchDynamicHeaders}).</b> Any other column whose header
 *       ends in "Id", "Name", "Key", or "Code" (and isn't a repeating "#1"/"#2"-style instance
 *       column) is automatically captured under a metadata key derived directly from its own
 *       header text — e.g. "Price List Item Id" → {@code priceListItemId}, "Price Key" →
 *       {@code priceKey}, "Installment Plan Name" → {@code installmentPlanName}. This is what
 *       lets a brand-new sheet (or a new column on an existing sheet) get picked up without a
 *       code change: as long as its identifier columns are named sensibly, they're captured
 *       automatically.</li>
 * </ol>
 * Both mechanisms compare header text after {@link #normalize}, so wording/spacing/punctuation
 * differences ("Flat Offering ID" vs "flat_offering_id" vs "FlatOfferingId") don't matter.
 * <p>
 * This class is also used by {@code HybridSearchService} to pull the same fields back out of
 * stored metadata for debugging/citation purposes (see {@link #extract} / {@link #extractFromRaw}),
 * which likewise require no maintenance when new dynamic fields appear — they simply return
 * whatever non-structural metadata keys a chunk happens to carry.
 */
public final class ExcelCatalogFields {

    private ExcelCatalogFields() {
    }

    /** Header-row summary for the sheet a chunk came from — not column-matched, always stored as-is. */
    public static final String TAB_HEADER = "tabHeader";

    /** Metadata keys that describe the chunk itself (not a business field) and must never be treated
     *  as a catalog/identifier field, whether by alias matching or by the generic fallback. */
    private static final Set<String> STRUCTURAL_KEYS = Set.of(
            "source", "type", "category", "workbook", "sheet", "sheetType", "rowStart", "rowEnd");

    /** canonical metadata key -> acceptable header-text synonyms (compared after normalization). */
    private static final Map<String, List<String>> SYNONYMS = buildSynonyms();

    /** Every canonical catalog-identifier key with a hand-maintained alias, in definition order. */
    public static final List<String> IDENTIFIER_KEYS = List.copyOf(SYNONYMS.keySet());

    /** {@link #IDENTIFIER_KEYS} plus {@link #TAB_HEADER} — kept for callers that want the fixed
     *  (non-dynamic) set specifically; {@link #extract}/{@link #extractFromRaw} no longer rely on
     *  this list, since they scan for whatever's actually present instead. */
    public static final List<String> ALL_KEYS = Collections.unmodifiableList(
            concat(IDENTIFIER_KEYS, TAB_HEADER));

    private static Map<String, List<String>> buildSynonyms() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("offeringName", List.of("offering name", "offeringname", "offering"));
        m.put("externalId", List.of("external id", "externalid", "ext id"));
        m.put("flatOfferingId", List.of("flat offering id", "flatofferingid", "flat offering", "fo id", "foid",
                "offering id", "offeringid"));
        m.put("changeRequestId", List.of("change request id", "cr id", "changerequestid", "crid", "change request"));
        m.put("bundleId", List.of("bundle id", "bundleid"));
        m.put("bundleName", List.of("bundle name", "bundlename"));
        m.put("tariffName", List.of("tariff name", "tariffname"));
        m.put("discountId", List.of("discount id", "discountid", "disc id", "discid"));
        m.put("discountName", List.of("discount name", "discountname"));
        m.put("ruleId", List.of("rule id", "ruleid"));
        m.put("ruleName", List.of("rule name", "rulename"));
        m.put("relationId", List.of("relation id", "relationid"));
        return Collections.unmodifiableMap(m);
    }

    private static List<String> concat(List<String> list, String extra) {
        List<String> out = new ArrayList<>(list);
        out.add(extra);
        return out;
    }

    /** Lowercases and strips everything but letters/digits, so "Flat Offering ID", "flat_offering_id", and
     *  "FlatOfferingId" all normalize to the same token before synonym comparison. */
    public static String normalize(String header) {
        return header == null ? "" : header.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Matches a sheet's header row against {@link #SYNONYMS}, returning canonical field name ->
     * column index for every header that matched. Computed once per sheet (not per-chunk/row)
     * since the header row doesn't change within a sheet. First matching header column wins if a
     * sheet has duplicate/ambiguous headers.
     */
    public static Map<String, Integer> matchHeaders(List<String> headers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        for (int i = 0; i < headers.size(); i++) {
            String normalizedHeader = normalize(headers.get(i));
            if (normalizedHeader.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<String>> entry : SYNONYMS.entrySet()) {
                String canonical = entry.getKey();
                if (result.containsKey(canonical)) {
                    continue; // first matching column wins
                }
                for (String synonym : entry.getValue()) {
                    if (normalize(synonym).equals(normalizedHeader)) {
                        result.put(canonical, i);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Matches a sheet's header row against an ops-configured header→metadataKey map
     * (rag.excel.business-fields), skipping any column already claimed (by {@link #matchHeaders}
     * or a previous layer). Comparison is by {@link #normalize}, so "TUTI", "tuti", and "T U T I"
     * all match a configured "TUTI" entry the same way. This is the layer that lets someone add
     * support for a new business field via application.yml alone, with zero code change, for a
     * column whose header doesn't already fit a known alias or the generic *Id/*Name/*Key/*Code
     * fallback (see {@link #matchDynamicHeaders}).
     *
     * @param headers        the sheet's header row
     * @param businessFields configured header text -> desired metadata key (rag.excel.business-fields)
     * @param claimedColumns column indices already assigned by an earlier layer — skipped here
     */
    public static Map<String, Integer> matchConfiguredFields(List<String> headers, Map<String, String> businessFields,
                                                               Set<Integer> claimedColumns) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (headers == null || businessFields == null || businessFields.isEmpty()) {
            return result;
        }
        Set<Integer> claimed = claimedColumns == null ? Set.of() : claimedColumns;

        // Normalize the configured map once: normalized header text -> desired metadata key.
        Map<String, String> normalizedConfig = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : businessFields.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            normalizedConfig.put(normalize(entry.getKey()), entry.getValue());
        }

        for (int i = 0; i < headers.size(); i++) {
            if (claimed.contains(i)) {
                continue;
            }
            String normalizedHeader = normalize(headers.get(i));
            String metadataKey = normalizedConfig.get(normalizedHeader);
            if (metadataKey == null || STRUCTURAL_KEYS.contains(metadataKey)
                    || metadataKey.equals(TAB_HEADER) || result.containsKey(metadataKey)) {
                continue; // no configured match, or it would clobber a structural/duplicate key
            }
            result.put(metadataKey, i);
        }
        return result;
    }


    /* * ends in "Id", "Name", "Key", or "Code" once normalized, and isn't a repeating-instance
     * column (e.g. "Characteristic #1", "Min Quantity #2") — those are numbered per-condition
     * columns rather than per-row identifiers, and there can be many near-duplicates of them.
     */
    public static boolean looksLikeIdentifierHeader(String header) {
        if (header == null || header.contains("#")) {
            return false;
        }
        String normalized = normalize(header);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.endsWith("id") || normalized.endsWith("name")
                || normalized.endsWith("key") || normalized.endsWith("code");
    }

    /**
     * Derives a camelCase metadata key directly from a header's own text — "Price List Item Id"
     * → "priceListItemId", "Price Key" → "priceKey" — so a new sheet's identifier-looking columns
     * are captured under a predictable, self-describing key without needing an entry in
     * {@link #SYNONYMS}.
     */
    public static String toDynamicKey(String header) {
        if (header == null) {
            return "";
        }
        String[] tokens = header.trim().split("[^A-Za-z0-9]+");
        StringBuilder key = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (key.length() == 0) {
                key.append(token.toLowerCase());
            } else {
                key.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1).toLowerCase());
            }
        }
        return key.toString();
    }

    /**
     * Generic fallback for header columns that {@link #matchHeaders} didn't already claim: any
     * remaining identifier-looking column (per {@link #looksLikeIdentifierHeader}) is captured
     * under a key derived by {@link #toDynamicKey}, skipping anything that would collide with a
     * structural or already-known canonical key. This is what lets a brand-new sheet's ID/Name/
     * Key/Code columns get stored automatically, with no code change required.
     *
     * @param headers        the sheet's header row
     * @param claimedColumns column indices already assigned by {@link #matchHeaders} — skipped here
     */
    public static Map<String, Integer> matchDynamicHeaders(List<String> headers, Set<Integer> claimedColumns) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        Set<Integer> claimed = claimedColumns == null ? Set.of() : claimedColumns;
        for (int i = 0; i < headers.size(); i++) {
            if (claimed.contains(i)) {
                continue;
            }
            String header = headers.get(i);
            if (!looksLikeIdentifierHeader(header)) {
                continue;
            }
            String key = toDynamicKey(header);
            if (key.isEmpty() || STRUCTURAL_KEYS.contains(key) || IDENTIFIER_KEYS.contains(key)
                    || key.equals(TAB_HEADER) || key.equals(ID_RANGE_NOTE) || result.containsKey(key)) {
                continue; // avoid clobbering reserved/canonical keys or duplicate derived keys
            }
            result.put(key, i);
        }
        return result;
    }

    /** Suffix appended to a field's canonical/derived key to name its reserved-ID-range-start
     *  metadata entry, e.g. field {@code ruleId} → range-start key {@code ruleIdRangeStart}. */
    public static final String RANGE_START_SUFFIX = "RangeStart";

    /** Combined raw range-note text for a sheet, kept verbatim for human debugging alongside the
     *  parsed {@link #RANGE_START_SUFFIX} values. */
    public static final String ID_RANGE_NOTE = "idRangeNote";

    /**
     * Matches reserved-block notes some sheets carry in a header cell, documenting the numeric ID
     * range that sheet's identifiers are allocated from — e.g. "Rule ID range: 71320000" or
     * "Discount ID range: 79200000 External ID range: 220000000" (several notes can appear back to
     * back in one cell). Group 1 is the label ("Rule ID", "Discount ID", ...), group 2 the starting
     * number.
     */
    private static final Pattern ID_RANGE_PATTERN =
            Pattern.compile("([A-Za-z][A-Za-z .]*?)\\s+range\\s*:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Scans a sheet's header row for "X range: NNNNNNNN"-style notes and returns the field-specific
     * range-start values it found, keyed as {@code <derivedFieldKey>RangeStart} (e.g. "Rule ID
     * range: 71320000" → {@code ruleIdRangeStart} → "71320000"). These document each sheet's
     * reserved ID block (different sheets draw from different, non-overlapping ranges), which is
     * useful context for spotting a misassigned or miscategorized ID during debugging. Values are
     * kept as strings (not parsed to numbers) since they're stored as chunk metadata alongside
     * every other field here.
     */
    public static Map<String, String> parseIdRangeHints(List<String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            Matcher matcher = ID_RANGE_PATTERN.matcher(header);
            while (matcher.find()) {
                String label = matcher.group(1).trim();
                String rangeStart = matcher.group(2);
                String fieldKey = toDynamicKey(label);
                if (!fieldKey.isEmpty()) {
                    out.putIfAbsent(fieldKey + RANGE_START_SUFFIX, rangeStart);
                }
            }
        }
        return out;
    }

    /** Returns the raw matched "X range: NNNNNNNN" note text(s) found in a sheet's header row,
     *  joined for storage under {@link #ID_RANGE_NOTE} — kept verbatim (not parsed) so a human
     *  reading a chunk's metadata sees the original wording, not just the derived key/value. */
    public static String extractRawIdRangeNote(List<String> headers) {
        if (headers == null) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            Matcher matcher = ID_RANGE_PATTERN.matcher(header);
            while (matcher.find()) {
                if (combined.length() > 0) {
                    combined.append("; ");
                }
                combined.append(matcher.group().trim());
            }
        }
        return combined.toString();
    }

    /** Pulls every populated non-structural metadata field back out of a LangChain4j {@link Metadata}
     *  object (vector-search leg) — includes both alias-matched and dynamically-derived fields,
     *  since it works by exclusion rather than by enumerating a fixed key list. */
    public static Map<String, String> extract(Metadata metadata) {
        Map<String, String> out = new LinkedHashMap<>();
        if (metadata == null) {
            return out;
        }
        metadata.toMap().forEach((key, value) -> {
            if (!STRUCTURAL_KEYS.contains(key) && value != null && !String.valueOf(value).isBlank()) {
                out.put(key, String.valueOf(value));
            }
        });
        return out;
    }

    /** Pulls every populated non-structural metadata field back out of a raw parsed-JSON metadata
     *  map (BM25/keyword leg) — same exclusion-based approach as {@link #extract}. */
    public static Map<String, String> extractFromRaw(Map<String, Object> rawMetadata) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawMetadata == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : rawMetadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!STRUCTURAL_KEYS.contains(key) && value != null && !value.toString().isBlank()) {
                out.put(key, value.toString());
            }
        }
        return out;
    }
}

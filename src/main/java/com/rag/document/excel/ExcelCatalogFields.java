package com.rag.document.excel;

import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
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
        // "Price Key" and "Price Key Id" are the same business concept across different workbook
        // versions/sheets, but without this alias they'd derive to two different dynamic keys
        // (priceKey vs priceKeyId — see matchDynamicHeaders/toDynamicKey), fragmenting retrieval
        // and filtering for what's really one identifier. Both header spellings now resolve here.
        m.put("priceKey", List.of("price key", "pricekey", "price key id", "pricekeyid"));
        return Collections.unmodifiableMap(m);
    }

    /**
     * Maps a dynamic-fallback key a previous version of {@link #matchDynamicHeaders} would have
     * derived for a header that has <em>since</em> been given a hand-maintained alias in
     * {@link #SYNONYMS} (so a fresh ingest of the same header now lands on the canonical key
     * instead). Add an entry here whenever that happens, so already-ingested chunks can be
     * re-tagged onto the canonical key a fresh ingest would now produce — see
     * {@link #reconcileLegacyKeys} and the metadata backfill job that applies it to existing rows.
     * <p>
     * Currently: a "Price Key Id" header column, ingested before {@code priceKey} had an alias
     * covering that spelling, would have been dynamically derived as {@code priceKeyId} — distinct
     * from the {@code priceKey} key a "Price Key" column (or a fresh ingest of "Price Key Id")
     * produces now. This reconciles the two onto {@code priceKey}.
     */
    public static final Map<String, String> LEGACY_KEY_ALIASES = Map.of(
            "priceKeyId", "priceKey"
    );

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
                    || key.equals(TAB_HEADER) || key.equals(ID_RANGE_NOTE) || key.equals(RANGE_WARNINGS_KEY)
                    || key.equals(HAS_RANGE_WARNING) || key.endsWith(RANGE_START_SUFFIX)
                    || key.endsWith(RANGE_END_SUFFIX) || key.endsWith(RANGE_WARNING_SUFFIX)
                    || LEGACY_KEY_ALIASES.containsKey(key) || result.containsKey(key)) {
                continue; // avoid clobbering reserved/canonical keys or duplicate derived keys
            }
            result.put(key, i);
        }
        return result;
    }

    /**
     * Renames any {@link #LEGACY_KEY_ALIASES} entry present in {@code metadata} onto its canonical
     * key in place — e.g. a chunk stamped {@code priceKeyId} before the {@code priceKey} alias
     * existed becomes {@code priceKey}, matching what a fresh ingest of the same header produces
     * today. If the canonical key is already present (and non-blank) the legacy value is simply
     * dropped rather than overwriting it, since the canonical value is presumed to be the
     * up-to-date one.
     * <p>
     * Used both defensively at fresh-ingestion time (a legacy key should never actually appear
     * there anymore, but this keeps the two code paths guaranteed-consistent) and by the metadata
     * backfill job to re-tag rows ingested before this alias existed.
     *
     * @return true if {@code metadata} was modified
     */
    public static boolean reconcileLegacyKeys(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, String> alias : LEGACY_KEY_ALIASES.entrySet()) {
            String legacyKey = alias.getKey();
            String canonicalKey = alias.getValue();
            if (!metadata.containsKey(legacyKey)) {
                continue;
            }
            String legacyValue = metadata.remove(legacyKey);
            changed = true;
            String existingCanonical = metadata.get(canonicalKey);
            if (existingCanonical == null || existingCanonical.isBlank()) {
                metadata.put(canonicalKey, legacyValue);
            }
        }
        return changed;
    }

    /** Suffix appended to a field's canonical/derived key to name its reserved-ID-range-start
     *  metadata entry, e.g. field {@code ruleId} → range-start key {@code ruleIdRangeStart}. */
    public static final String RANGE_START_SUFFIX = "RangeStart";

    /** Suffix appended to a field's canonical/derived key to name its reserved-ID-range-end
     *  metadata entry, when the sheet's note documents an upper bound too (e.g. "Rule ID range:
     *  71320000-71329999" → {@code ruleIdRangeEnd} = "71329999"). Absent when a sheet's note only
     *  gives a starting number, in which case the range is treated as open-ended (a floor only). */
    public static final String RANGE_END_SUFFIX = "RangeEnd";

    /** Suffix appended to a field's canonical/derived key naming the human-readable warning stamped
     *  on a chunk when that field's value falls outside its sheet's documented range, e.g.
     *  {@code ruleIdRangeWarning}. See {@link #checkRange}/{@link #applyRangeWarnings}. */
    public static final String RANGE_WARNING_SUFFIX = "RangeWarning";

    /** All per-field range warnings for a chunk, joined into one string for quick scanning without
     *  having to know every field's warning-key name in advance. */
    public static final String RANGE_WARNINGS_KEY = "rangeWarnings";

    /** "true" iff this chunk carries at least one entry under {@link #RANGE_WARNINGS_KEY} — lets
     *  callers (search filters, admin reports) cheaply find flagged rows without string-matching. */
    public static final String HAS_RANGE_WARNING = "hasRangeWarning";

    /** Combined raw range-note text for a sheet, kept verbatim for human debugging alongside the
     *  parsed {@link #RANGE_START_SUFFIX} values. */
    public static final String ID_RANGE_NOTE = "idRangeNote";

    /**
     * Matches reserved-block notes some sheets carry in a header cell, documenting the numeric ID
     * range that sheet's identifiers are allocated from — e.g. "Rule ID range: 71320000" (an
     * open-ended floor) or "Discount ID range: 79200000-79299999" / "Discount ID range: 79200000 to
     * 79299999" (a closed range), and several such notes can appear back to back in one cell.
     * Group 1 is the label ("Rule ID", "Discount ID", ...), group 2 the starting number, group 3
     * the optional ending number.
     */
    private static final Pattern ID_RANGE_PATTERN = Pattern.compile(
            "([A-Za-z][A-Za-z .]*?)\\s+range\\s*:?\\s*(\\d+)(?:\\s*(?:-|–|to)\\s*(\\d+))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Scans a sheet's header row for "X range: NNNNNNNN" / "X range: NNNNNNNN-MMMMMMMM"-style notes
     * and returns the field-specific range values it found, keyed as {@code <derivedFieldKey>RangeStart}
     * and (when an upper bound was given) {@code <derivedFieldKey>RangeEnd} — e.g. "Rule ID range:
     * 71320000" → {@code ruleIdRangeStart} → "71320000". These document each sheet's reserved ID
     * block (different sheets draw from different, non-overlapping ranges), which {@link #checkRange}
     * uses to flag a row whose ID doesn't actually fall in the block its sheet claims. Values are
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
                String rangeEnd = matcher.group(3);
                String fieldKey = toDynamicKey(label);
                if (!fieldKey.isEmpty()) {
                    out.putIfAbsent(fieldKey + RANGE_START_SUFFIX, rangeStart);
                    if (rangeEnd != null) {
                        out.putIfAbsent(fieldKey + RANGE_END_SUFFIX, rangeEnd);
                    }
                }
            }
        }
        return out;
    }

    /** Returns the raw matched "X range: NNNNNNNN[-MMMMMMMM]" note text(s) found in a sheet's header
     *  row, joined for storage under {@link #ID_RANGE_NOTE} — kept verbatim (not parsed) so a human
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

    /** A field key is eligible for range-checking if it's an actual identifier/business-field value
     *  — not a structural key, not the range metadata itself (its own *RangeStart/*RangeEnd/
     *  *RangeWarning entries), and not one of the sheet-level summary keys. Shared by
     *  {@link #applyRangeWarnings} so it only ever evaluates real candidate fields. */
    private static boolean isRangeCheckable(String key) {
        if (key == null || STRUCTURAL_KEYS.contains(key)) {
            return false;
        }
        if (key.equals(TAB_HEADER) || key.equals(ID_RANGE_NOTE)
                || key.equals(RANGE_WARNINGS_KEY) || key.equals(HAS_RANGE_WARNING)) {
            return false;
        }
        return !key.endsWith(RANGE_START_SUFFIX) && !key.endsWith(RANGE_END_SUFFIX)
                && !key.endsWith(RANGE_WARNING_SUFFIX);
    }

    /** Pulls the first run of digits (with an optional decimal point) out of a raw cell value, so
     *  values Excel/POI render as e.g. "71325001.0" (numeric cell) or "RULE-71325001" (mixed text)
     *  can still be compared against a documented numeric range. */
    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private static OptionalDouble parseNumeric(String raw) {
        if (raw == null) {
            return OptionalDouble.empty();
        }
        Matcher matcher = LEADING_NUMBER.matcher(raw.trim());
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** Renders a numeric string without a trailing ".0" when it's a whole number, purely for
     *  cleaner warning messages (POI numeric cells round-trip as e.g. "71325001.0"). */
    private static String trimNumeric(String raw) {
        OptionalDouble parsed = parseNumeric(raw);
        if (parsed.isEmpty()) {
            return raw;
        }
        double value = parsed.getAsDouble();
        return (value == Math.floor(value) && !Double.isInfinite(value))
                ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Checks one field's value against its sheet's documented range (if any), returning a
     * human-readable warning when the value falls outside it. With only a {@code *RangeStart} hint
     * (no documented upper bound), the range is treated as an open-ended floor — a value below it is
     * flagged, but nothing is above it. With both a start and an end, the value must fall inside
     * that closed interval. Non-numeric values, or fields with no documented range at all, are
     * silently skipped (returns empty) rather than flagged — this is a "does the sheet's own
     * documentation say something's off" check, not a general data-quality validator.
     *
     * @param fieldKey the metadata key the value is stored under, e.g. {@code ruleId}
     * @param value    the field's raw value for this row
     * @param hints    the sheet's range hints (as produced by {@link #parseIdRangeHints}, or simply
     *                 the chunk's own metadata map, since the hints live alongside the field values)
     */
    public static Optional<String> checkRange(String fieldKey, String value, Map<String, String> hints) {
        if (fieldKey == null || value == null || value.isBlank() || hints == null) {
            return Optional.empty();
        }
        String startStr = hints.get(fieldKey + RANGE_START_SUFFIX);
        if (startStr == null) {
            return Optional.empty(); // this field's sheet didn't document a range for it
        }
        OptionalDouble parsedValue = parseNumeric(value);
        OptionalDouble parsedStart = parseNumeric(startStr);
        if (parsedValue.isEmpty() || parsedStart.isEmpty()) {
            return Optional.empty();
        }
        double v = parsedValue.getAsDouble();
        double start = parsedStart.getAsDouble();
        String endStr = hints.get(fieldKey + RANGE_END_SUFFIX);
        OptionalDouble parsedEnd = endStr == null ? OptionalDouble.empty() : parseNumeric(endStr);

        if (parsedEnd.isPresent()) {
            double end = parsedEnd.getAsDouble();
            if (v < start || v > end) {
                return Optional.of(String.format("%s=%s is outside its sheet's documented range (%s-%s)",
                        fieldKey, trimNumeric(value), trimNumeric(startStr), trimNumeric(endStr)));
            }
        } else if (v < start) {
            return Optional.of(String.format("%s=%s is below its sheet's documented range start (%s)",
                    fieldKey, trimNumeric(value), trimNumeric(startStr)));
        }
        return Optional.empty();
    }

    /**
     * Scans an already-populated metadata map — field values plus any {@code *RangeStart}/
     * {@code *RangeEnd} hints already present on it (see {@link #parseIdRangeHints}) — and stamps
     * per-field {@code *RangeWarning} entries, a combined {@link #RANGE_WARNINGS_KEY}, and
     * {@link #HAS_RANGE_WARNING} onto it for every field whose value falls outside its documented
     * range (per {@link #checkRange}).
     * <p>
     * Used both at fresh-ingestion time ({@link ExcelStructuredChunker}, right after a row's fields
     * and its sheet's range hints are both known) and by the metadata backfill job for chunks
     * ingested before this feature existed — same logic in both places, so a backfilled chunk ends
     * up identical to what a fresh ingest would have produced.
     *
     * @return true if the map was modified (i.e. at least one new/changed warning was stamped)
     */
    public static boolean applyRangeWarnings(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        List<String> warnings = new ArrayList<>();
        boolean changed = false;
        for (String field : List.copyOf(metadata.keySet())) {
            if (!isRangeCheckable(field)) {
                continue;
            }
            Optional<String> warning = checkRange(field, metadata.get(field), metadata);
            if (warning.isPresent()) {
                String warningKey = field + RANGE_WARNING_SUFFIX;
                if (!warning.get().equals(metadata.get(warningKey))) {
                    metadata.put(warningKey, warning.get());
                    changed = true;
                }
                warnings.add(warning.get());
            }
        }
        if (!warnings.isEmpty()) {
            String combined = String.join("; ", warnings);
            if (!combined.equals(metadata.get(RANGE_WARNINGS_KEY))) {
                metadata.put(RANGE_WARNINGS_KEY, combined);
                changed = true;
            }
            if (!"true".equals(metadata.get(HAS_RANGE_WARNING))) {
                metadata.put(HAS_RANGE_WARNING, "true");
                changed = true;
            }
        }
        return changed;
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

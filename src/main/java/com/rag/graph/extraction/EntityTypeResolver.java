package com.rag.graph.extraction;

/**
 * Derives a canonical, human-readable entity type from a chunk-metadata field key — both the
 * hand-maintained aliases ({@code flatOfferingId}, {@code changeRequestId}, {@code ruleId},
 * {@code ruleName}, ...) and the generically auto-detected {@code *Id}/{@code *Name}/{@code *Key}/
 * {@code *Code} fields, both produced by {@link com.rag.document.excel.ExcelCatalogFields}.
 * <p>
 * The suffix is stripped so that an entity referenced by id in one sheet and by name in another
 * still resolves to the <em>same</em> type (e.g. {@code ruleId} and {@code ruleName} both become
 * {@code "Rule"}) — deliberately not the same graph node (id and name values differ), but the same
 * type, so graph search/filtering by type finds both.
 */
public final class EntityTypeResolver {

    private EntityTypeResolver() {
    }

    private static final String[] STRIPPABLE_SUFFIXES = {"Id", "Name", "Key", "Code"};

    /**
     * @param metadataKey the camelCase metadata field key, e.g. "flatOfferingId", "tuti"
     * @return a capitalized type name, e.g. "FlatOffering", "Tuti"
     */
    public static String resolveType(String metadataKey) {
        if (metadataKey == null || metadataKey.isBlank()) {
            return "Unknown";
        }
        String base = metadataKey;
        for (String suffix : STRIPPABLE_SUFFIXES) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        if (base.isBlank()) {
            base = metadataKey; // suffix WAS the whole key (e.g. a field literally named "Id") — keep it as-is
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }
}

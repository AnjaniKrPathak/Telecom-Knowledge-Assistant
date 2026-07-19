package com.rag.document.excel;

/**
 * Classifies an Excel <b>sheet/tab</b> into a canonical business-domain type based on its tab
 * name, independent of whatever the column headers on that sheet turn out to be. This is deliberately
 * separate from {@link ExcelCatalogFields} (which matches column headers): a sheet's tab name tells
 * you what kind of records live on it even before you've looked at a single header cell, which is
 * useful for:
 * <ul>
 *   <li><b>Debugging</b> — a retrieved chunk can say "this came from a Refills sheet" instead of
 *       just a raw tab name like "PrP Optional Offering", which may not be self-explanatory to
 *       someone reading logs/citations.</li>
 *   <li><b>Retrieval</b> — grouping semantically-equivalent tabs across workbook versions (e.g. a
 *       future workbook might rename "PrP Optional Offering" to "Prepaid Optional Offerings" — both
 *       should still resolve to the same canonical {@code optionalOffering} type).</li>
 * </ul>
 * Matching is substring-based on a normalized (lowercased, non-alphanumeric-stripped) tab name, and
 * checked in order from most specific to least specific, so a tab like "Complex Flat Rules" resolves
 * to {@link #COMPLEX_FLAT_RULE} rather than falling through to the more generic {@link #RULE}.
 */
public final class ExcelSheetType {

    private ExcelSheetType() {
    }

    public static final String OFFERING = "offering";
    public static final String OPTIONAL_OFFERING = "optionalOffering";
    public static final String REFILL = "refill";
    public static final String RELATIONS_OVERRIDE = "relationsOverride";
    public static final String CHARACTERISTICS_OVERRIDE = "characteristicsOverride";
    public static final String PRICE_LIST_ITEM = "priceListItem";
    public static final String COMPLEX_FLAT_RULE = "complexFlatRule";
    public static final String DISCOUNT = "discount";
    public static final String RULE = "rule";
    public static final String GENERAL = "general";

    /**
     * Ordered most-specific-first so compound tab names (e.g. "Complex Flat Rules", which contains
     * the substring "rule") resolve to their specific type before falling through to a generic one.
     */
    private static final Object[][] PATTERNS = {
            {OPTIONAL_OFFERING, new String[]{"optionaloffering"}},
            {RELATIONS_OVERRIDE, new String[]{"relationsoverride", "relationoverride"}},
            {CHARACTERISTICS_OVERRIDE, new String[]{"characteristicsoverride", "characteristicoverride"}},
            {PRICE_LIST_ITEM, new String[]{"pricelist"}},
            {COMPLEX_FLAT_RULE, new String[]{"complexflatrule", "complexrule"}},
            {REFILL, new String[]{"refill"}},
            {DISCOUNT, new String[]{"discount"}},
            {RULE, new String[]{"rule"}},
            {OFFERING, new String[]{"offering"}},
    };

    /** @param sheetName the sheet/tab name as it appears in the workbook (may be null) */
    public static String classify(String sheetName) {
        String normalized = normalize(sheetName);
        if (normalized.isEmpty()) {
            return GENERAL;
        }
        for (Object[] pattern : PATTERNS) {
            String canonical = (String) pattern[0];
            for (String needle : (String[]) pattern[1]) {
                if (normalized.contains(needle)) {
                    return canonical;
                }
            }
        }
        return GENERAL;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

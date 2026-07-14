package com.rag.search;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses PostgreSQL's tsvector text representation — e.g. {@code "'error':1,7 'code':3"} —
 * into a lexeme → term-frequency map. This is what lets BM25 read real per-document term
 * frequencies straight out of a value the database already returns, with no extra
 * per-document SQL calls and no additional extensions.
 */
final class TsVectorParser {

    // 'lexeme':pos,pos,pos — lexemes are single-quoted; an embedded quote is doubled ('').
    private static final Pattern ENTRY_PATTERN = Pattern.compile("'((?:[^']|'')*)':([0-9]+(?:,[0-9]+)*)");

    private TsVectorParser() {
    }

    static Map<String, Integer> parse(String tsvectorText) {
        Map<String, Integer> termFrequencies = new LinkedHashMap<>();
        if (tsvectorText == null || tsvectorText.isBlank()) {
            return termFrequencies;
        }
        Matcher matcher = ENTRY_PATTERN.matcher(tsvectorText);
        while (matcher.find()) {
            String lexeme = matcher.group(1).replace("''", "'");
            int occurrences = matcher.group(2).split(",").length;
            termFrequencies.merge(lexeme, occurrences, Integer::sum);
        }
        return termFrequencies;
    }
}

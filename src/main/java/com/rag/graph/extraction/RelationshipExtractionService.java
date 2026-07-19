package com.rag.graph.extraction;

import com.rag.graph.model.GraphEntity;
import com.rag.graph.model.GraphRelationType;
import com.rag.graph.model.GraphRelationship;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers directed edges between entities that co-occur in the same chunk/row.
 * <p>
 * This is a lightweight heuristic, not real relation extraction/NLP: if the chunk's text contains
 * one of a small set of directional keywords ("depends on", "triggers", "replaces", ...), every
 * entity pair extracted from that chunk is linked with the corresponding {@link GraphRelationType},
 * directed from the first-mentioned entity to the second (by extraction order — see
 * {@code EntityExtractionService}). If no keyword is found, entities are still linked, but as a
 * plain undirected {@link GraphRelationType#RELATED_TO} co-occurrence — still useful for graph
 * search/neighbor expansion, just without an implied direction of dependency.
 * <p>
 * Precision note: for a chunk with more than two entities, EVERY pairwise combination gets the
 * same inferred relationship type when a keyword is found — the heuristic doesn't try to work out
 * which specific pair the keyword's phrase was actually about. This is a reasonable trade-off for
 * Excel row-level chunks (which are narrow — usually just the columns of one record) but is looser
 * for wide narrative paragraphs mentioning several identifiers; treat root-cause/impact/path
 * results built from narrative-derived edges as leads to verify, not verified dependencies.
 */
@Component
public class RelationshipExtractionService {

    /** Checked in order — first match wins. Longer/more specific phrases are listed before shorter ones
     *  that could otherwise shadow them (e.g. "leads to" before a hypothetical bare "to"). */
    private static final Map<String, GraphRelationType> KEYWORD_TO_RELATION_TYPE = buildKeywordMap();

    private static Map<String, GraphRelationType> buildKeywordMap() {
        Map<String, GraphRelationType> m = new LinkedHashMap<>();
        m.put("depends on", GraphRelationType.DEPENDS_ON);
        m.put("dependent on", GraphRelationType.DEPENDS_ON);
        m.put("requires", GraphRelationType.DEPENDS_ON);
        m.put("needs", GraphRelationType.DEPENDS_ON);
        m.put("triggers", GraphRelationType.TRIGGERS);
        m.put("causes", GraphRelationType.TRIGGERS);
        m.put("leads to", GraphRelationType.TRIGGERS);
        m.put("results in", GraphRelationType.TRIGGERS);
        m.put("replaces", GraphRelationType.REPLACES);
        m.put("supersedes", GraphRelationType.REPLACES);
        m.put("overrides", GraphRelationType.OVERRIDES);
        m.put("overridden by", GraphRelationType.OVERRIDES);
        m.put("maps to", GraphRelationType.RELATES_TO);
        m.put("mapped to", GraphRelationType.RELATES_TO);
        m.put("relates to", GraphRelationType.RELATES_TO);
        m.put("related to", GraphRelationType.RELATES_TO);
        m.put("associated with", GraphRelationType.RELATES_TO);
        return m;
    }

    /**
     * @param text     the chunk's raw text (searched, case-insensitively, for a directional keyword)
     * @param entities entities already extracted from this same chunk (order = mention order)
     * @param source   the chunk's source, stamped onto every produced relationship
     * @return every pairwise relationship for this chunk (empty if fewer than 2 entities)
     */
    public List<GraphRelationship> extractRelationships(String text, List<GraphEntity> entities, String source) {
        List<GraphRelationship> relationships = new ArrayList<>();
        if (entities == null || entities.size() < 2) {
            return relationships;
        }

        String lower = text == null ? "" : text.toLowerCase();
        GraphRelationType relType = GraphRelationType.RELATED_TO;
        String matchedKeyword = null;
        for (Map.Entry<String, GraphRelationType> entry : KEYWORD_TO_RELATION_TYPE.entrySet()) {
            if (lower.contains(entry.getKey())) {
                relType = entry.getValue();
                matchedKeyword = entry.getKey();
                break;
            }
        }
        String evidence = matchedKeyword == null ? null : snippetAround(text, matchedKeyword);

        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                GraphEntity a = entities.get(i);
                GraphEntity b = entities.get(j);
                if (a.id().equals(b.id())) {
                    continue; // same node twice in one chunk (e.g. an id and name field resolving to the same id) — skip self-loop
                }
                relationships.add(new GraphRelationship(a.id(), b.id(), relType.name(), source, evidence));
            }
        }
        return relationships;
    }

    /** Up to ~120 characters of context around the matched keyword, for a human to sanity-check the inference. */
    private String snippetAround(String text, String keyword) {
        int idx = text.toLowerCase().indexOf(keyword);
        if (idx < 0) {
            return null;
        }
        int start = Math.max(0, idx - 50);
        int end = Math.min(text.length(), idx + keyword.length() + 50);
        String snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + snippet + (end < text.length() ? "…" : "");
    }
}

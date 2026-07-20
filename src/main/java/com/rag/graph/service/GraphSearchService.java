package com.rag.graph.service;

import com.rag.config.GraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Free-text entity lookup and neighbor expansion — the entry point for "find this thing in the
 * graph" and "what's connected to it", used both directly (GraphController) and by the other
 * analysis services to resolve a human-friendly search term into the entity id they need.
 */
@Service
@RequiredArgsConstructor
public class GraphSearchService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphProperties graphProperties;

    /**
     * Case-insensitive substring match against entity id/value, ranked by how often the entity has
     * been seen across ingested chunks (a rough proxy for how central/important it is).
     */
    public List<Map<String, Object>> searchEntities(String queryText, int limit) {
        String cypher = """
                MATCH (e:Entity)
                WHERE toLower(e.value) CONTAINS toLower($q) OR toLower(e.id) CONTAINS toLower($q)
                RETURN e.id AS id, e.type AS type, e.value AS value, e.occurrences AS occurrences
                ORDER BY e.occurrences DESC
                LIMIT $limit
                """;
        return knowledgeGraphService.query(cypher, Map.of("q", queryText == null ? "" : queryText, "limit", limit));
    }

    /** Every entity directly or transitively connected to {@code entityId} within {@code depth} hops (any relationship type, either direction). */
    public List<Map<String, Object>> neighbors(String entityId, int depth) {
        int safeDepth = clamp(depth, graphProperties.getMaxPathDepth());
        String cypher = """
                MATCH (e:Entity {id: $id})-[*1..%d]-(n:Entity)
                WHERE n.id <> $id
                RETURN DISTINCT n.id AS id, n.type AS type, n.value AS value, n.occurrences AS occurrences
                LIMIT 200
                """.formatted(safeDepth);
        return knowledgeGraphService.query(cypher, Map.of("id", entityId));
    }

    /** All documents/sheets this entity has been seen in. */
    public List<Map<String, Object>> documentsFor(String entityId) {
        String cypher = """
                MATCH (e:Entity {id: $id})-[r:APPEARS_IN]->(d:Document)
                RETURN d.id AS documentId, r.weight AS mentionCount
                ORDER BY mentionCount DESC
                """;
        return knowledgeGraphService.query(cypher, Map.of("id", entityId));
    }

    static int clamp(int depth, int max) {
        return Math.min(Math.max(depth, 1), max);
    }
}

package com.rag.graph.service;

import com.rag.config.GraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds how two entities relate through the graph, regardless of relationship type or direction —
 * useful for "is Rule X connected to Offering Y at all, and how" questions that dependency/impact
 * analysis (which only follow specific relationship types) don't answer.
 */
@Service
@RequiredArgsConstructor
public class PathFindingService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphProperties graphProperties;

    /** The single shortest connection between two entities (any relationship type/direction), if one exists within the configured hop ceiling. */
    public Optional<Map<String, Object>> shortestPath(String fromId, String toId) {
        int maxDepth = graphProperties.getMaxPathDepth();
        String cypher = """
                MATCH (a:Entity {id: $from}), (b:Entity {id: $to})
                MATCH p = shortestPath((a)-[*1..%d]-(b))
                RETURN [n IN nodes(p) | {id: n.id, type: n.type, value: n.value}] AS nodes,
                       [r IN relationships(p) | type(r)] AS relationshipTypes,
                       length(p) AS length
                """.formatted(maxDepth);
        List<Map<String, Object>> rows = knowledgeGraphService.query(cypher, Map.of("from", fromId, "to", toId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Every distinct shortest path between two entities (there can be more than one path of the minimum length) — capped at 10 for readability. */
    public List<Map<String, Object>> allShortestPaths(String fromId, String toId, int maxDepth) {
        int d = GraphSearchService.clamp(maxDepth, graphProperties.getMaxPathDepth());
        String cypher = """
                MATCH (a:Entity {id: $from}), (b:Entity {id: $to})
                MATCH p = allShortestPaths((a)-[*1..%d]-(b))
                RETURN [n IN nodes(p) | {id: n.id, type: n.type, value: n.value}] AS nodes,
                       [r IN relationships(p) | type(r)] AS relationshipTypes,
                       length(p) AS length
                LIMIT 10
                """.formatted(d);
        return knowledgeGraphService.query(cypher, Map.of("from", fromId, "to", toId));
    }
}

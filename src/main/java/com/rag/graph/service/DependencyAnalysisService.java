package com.rag.graph.service;

import com.rag.config.GraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Traverses explicit {@code DEPENDS_ON} edges (the directional relationship inferred by
 * {@code RelationshipExtractionService} from "depends on"/"requires"/"needs" phrasing) to answer
 * "what does X need" and "what needs X".
 */
@Service
@RequiredArgsConstructor
public class DependencyAnalysisService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphProperties graphProperties;

    /** What {@code entityId} depends on, transitively, up to {@code depth} hops — nearest first. */
    public List<Map<String, Object>> dependencies(String entityId, int depth) {
        int d = GraphSearchService.clamp(depth, graphProperties.getMaxDependencyDepth());
        String cypher = """
                MATCH path = (e:Entity {id: $id})-[:DEPENDS_ON*1..%d]->(dep:Entity)
                RETURN dep.id AS id, dep.type AS type, dep.value AS value, length(path) AS hops
                ORDER BY hops, dep.occurrences DESC
                """.formatted(d);
        return knowledgeGraphService.query(cypher, Map.of("id", entityId));
    }

    /** What depends on {@code entityId}, transitively, up to {@code depth} hops — nearest first. */
    public List<Map<String, Object>> dependents(String entityId, int depth) {
        int d = GraphSearchService.clamp(depth, graphProperties.getMaxDependencyDepth());
        String cypher = """
                MATCH path = (dependent:Entity)-[:DEPENDS_ON*1..%d]->(e:Entity {id: $id})
                RETURN dependent.id AS id, dependent.type AS type, dependent.value AS value, length(path) AS hops
                ORDER BY hops, dependent.occurrences DESC
                """.formatted(d);
        return knowledgeGraphService.query(cypher, Map.of("id", entityId));
    }
}

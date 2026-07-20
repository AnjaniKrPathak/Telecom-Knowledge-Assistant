package com.rag.graph.service;

import com.rag.config.GraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Given a "symptom" entity (e.g. a rule or offering reported as misbehaving), walks the
 * dependency/trigger chain upstream to surface candidate root causes.
 * <p>
 * This is a heuristic ranking, not a certified diagnosis: a candidate ancestor is considered
 * "more root" the fewer further upstream dependencies it itself has (few/no {@code DEPENDS_ON}
 * edges of its own = likely near the start of the chain) and the farther it sits from the symptom
 * (more hops = more foundational). Both signals are approximations of "root cause" that depend
 * entirely on how completely the corpus's DEPENDS_ON/TRIGGERS relationships were captured — treat
 * the ranked list as leads to investigate, in order, not a definitive answer.
 */
@Service
@RequiredArgsConstructor
public class RootCauseAnalysisService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphProperties graphProperties;

    public List<Map<String, Object>> findRootCauses(String symptomEntityId, int depth) {
        int d = GraphSearchService.clamp(depth, graphProperties.getMaxDependencyDepth());
        String cypher = """
                MATCH path = (symptom:Entity {id: $id})-[:DEPENDS_ON|TRIGGERS*1..%d]-(ancestor:Entity)
                WHERE ancestor.id <> $id
                WITH ancestor, min(length(path)) AS closestHop, max(length(path)) AS farthestHop
                OPTIONAL MATCH (ancestor)-[:DEPENDS_ON]->(further:Entity)
                WITH ancestor, closestHop, farthestHop, count(DISTINCT further) AS furtherDependencyCount
                RETURN ancestor.id AS id, ancestor.type AS type, ancestor.value AS value,
                       closestHop, farthestHop, furtherDependencyCount
                ORDER BY furtherDependencyCount ASC, farthestHop DESC
                LIMIT 25
                """.formatted(d);
        return knowledgeGraphService.query(cypher, Map.of("id", symptomEntityId));
    }
}

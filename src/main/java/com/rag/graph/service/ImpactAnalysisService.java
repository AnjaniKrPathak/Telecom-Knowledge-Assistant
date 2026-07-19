package com.rag.graph.service;

import com.rag.config.GraphProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Given an entity that's about to change (an offering, rule, discount, ...), finds everything
 * downstream that could be affected: anything that {@code DEPENDS_ON} it, anything it
 * {@code TRIGGERS}, and anything it {@code OVERRIDES} — traversed transitively up to
 * {@code rag.graph.max-dependency-depth} hops, and grouped by which document(s) each impacted
 * entity appears in so the result can point straight at what to re-review.
 * <p>
 * Same heuristic caveat as root-cause analysis: this is only as complete as the DEPENDS_ON/
 * TRIGGERS/OVERRIDES edges captured at ingestion time (co-occurrence + keyword inference — see
 * {@code RelationshipExtractionService}), not a guaranteed-complete blast radius.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpactAnalysisService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphProperties graphProperties;

    public List<Map<String, Object>> analyzeImpact(String changedEntityId, int depth) {

        int d = GraphSearchService.clamp(depth, graphProperties.getMaxDependencyDepth());
        log.info("Impact analysis for {} up to {} hops before", changedEntityId, d);
        String cypher = """
                MATCH path = (changed:Entity {id: $id})<-[:DEPENDS_ON|TRIGGERS|OVERRIDES*1..%d]-(impacted:Entity)
                WHERE impacted.id <> $id
                WITH impacted, min(length(path)) AS hops
                OPTIONAL MATCH (impacted)-[:APPEARS_IN]->(doc:Document)
                WITH impacted.id AS id, impacted.type AS type, impacted.value AS value,
                     impacted.occurrences AS occurrences, hops, collect(DISTINCT doc.id) AS documents
                RETURN id, type, value, hops, documents
                ORDER BY hops, occurrences DESC
                """.formatted(d);
        log.info("Impact analysis cypher: {} After ", cypher);
        return knowledgeGraphService.query(cypher, Map.of("id", changedEntityId));
    }
}

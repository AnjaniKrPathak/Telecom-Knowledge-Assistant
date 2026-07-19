package com.rag.graph.service;

import com.rag.config.Neo4jProperties;
import com.rag.graph.model.GraphEntity;
import com.rag.graph.model.GraphRelationType;
import com.rag.graph.model.GraphRelationship;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The only class in this project that talks to Neo4j directly — every other graph service
 * (search, dependency/root-cause/impact analysis, path finding, ingestion, backfill) goes through
 * this one for reads and writes, so Cypher construction and session handling live in exactly one
 * place.
 * <p>
 * All nodes carry a single {@code :Entity} (or {@code :Document}) label with a {@code type}
 * property distinguishing business-domain kinds (Offering, Rule, ChangeRequest, ...), rather than
 * a dynamic Neo4j label per type. Neo4j/Cypher has no way to parameterize a node label or a
 * relationship type in a query — only property values can be bound parameters — so a dynamic
 * label/rel-type would require either string-concatenating unvalidated input into Cypher (an
 * injection risk) or the optional APOC plugin (extra ops burden this project's docker-compose
 * intentionally avoids for a single new container). Filtering/grouping by a property behaves
 * identically to filtering by label for every query this feature needs.
 * <p>
 * Relationship types DO need to be embedded directly in the Cypher text for the same reason, so
 * {@link #upsertRelationships} validates every one against {@link GraphRelationType}'s closed
 * enum before splicing it in — see {@link #isSafeRelType}.
 * <p>
 * Every query method projects an explicit set of scalar/list/map fields in its {@code RETURN}
 * clause rather than returning raw {@code Node}/{@code Relationship} objects — {@link Record#asMap()}
 * on a raw node includes internal driver types that don't round-trip cleanly through Jackson: all
 * callers (GraphSearchService, DependencyAnalysisService, etc.) write Cypher that already returns
 * plain values, so results are safe to serialize straight to JSON in GraphController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final Driver driver;
    private final Neo4jProperties properties;

    private SessionConfig sessionConfig() {
        return SessionConfig.builder().withDatabase(properties.getDatabase()).build();
    }

    /** Runs an arbitrary write statement (used internally; exposed for GraphBackfillService-style callers). */
    public void run(String cypher, Map<String, Object> params) {
        try (Session session = driver.session(sessionConfig())) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    /** Runs an arbitrary read statement and projects every returned record to a plain map. */
    public List<Map<String, Object>> query(String cypher, Map<String, Object> params) {
        try (Session session = driver.session(sessionConfig())) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, params);
                List<Map<String, Object>> out = new ArrayList<>();
                while (result.hasNext()) {
                    Record record = result.next();
                    out.add(record.asMap());
                }
                return out;
            });
        }
    }

    /**
     * MERGEs one {@code :Entity} node per {@link GraphEntity}, keyed by its stable id. Repeated
     * mentions across ingestion runs bump an {@code occurrences} counter rather than duplicating
     * the node — this is what lets the same business identifier referenced from many
     * sheets/documents converge onto a single graph node.
     */
    public void upsertEntities(List<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = entities.stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.id());
                    row.put("type", e.type());
                    row.put("value", e.value());
                    return row;
                })
                .toList();

        String cypher = """
                UNWIND $rows AS row
                MERGE (e:Entity {id: row.id})
                ON CREATE SET e.type = row.type, e.value = row.value, e.occurrences = 1, e.firstSeenAt = timestamp()
                ON MATCH SET e.occurrences = coalesce(e.occurrences, 0) + 1, e.value = row.value
                SET e.lastSeenAt = timestamp()
                """;
        run(cypher, Map.of("rows", rows));
    }

    /**
     * Links every entity to a {@code :Document} node (MERGEd on {@code source}) via an
     * {@code APPEARS_IN} edge, so "which document(s) mention X" and impact/root-cause result
     * grouping by document both work. Entities with a blank/null source are skipped.
     */
    public void linkEntitiesToDocument(List<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = entities.stream()
                .filter(e -> e.source() != null && !e.source().isBlank())
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entityId", e.id());
                    row.put("source", e.source());
                    return row;
                })
                .toList();
        if (rows.isEmpty()) {
            return;
        }

        String cypher = """
                UNWIND $rows AS row
                MERGE (d:Document {id: row.source})
                MERGE (e:Entity {id: row.entityId})
                MERGE (e)-[r:APPEARS_IN]->(d)
                ON CREATE SET r.weight = 1
                ON MATCH SET r.weight = coalesce(r.weight, 0) + 1
                """;
        run(cypher, Map.of("rows", rows));
    }

    /**
     * MERGEs every relationship, grouped by type (one Cypher statement per distinct type present,
     * since the type must be embedded in the query text — see class javadoc). Any relationship
     * whose {@code relType} isn't one of {@link GraphRelationType}'s exact names is dropped with a
     * warning rather than risking an unsafe/invalid Cypher fragment.
     */
    public void upsertRelationships(List<GraphRelationship> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return;
        }

        Map<String, List<GraphRelationship>> byType = relationships.stream()
                .filter(r -> {
                    boolean safe = isSafeRelType(r.relType());
                    if (!safe) {
                        log.warn("Dropping relationship with unrecognized relType='{}' (from={}, to={})",
                                r.relType(), r.fromId(), r.toId());
                    }
                    return safe;
                })
                .collect(Collectors.groupingBy(GraphRelationship::relType));

        for (Map.Entry<String, List<GraphRelationship>> entry : byType.entrySet()) {
            String relType = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue().stream()
                    .map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("from", r.fromId());
                        row.put("to", r.toId());
                        row.put("source", r.source() == null ? "" : r.source());
                        row.put("evidence", r.evidence() == null ? "" : r.evidence());
                        return row;
                    })
                    .toList();

            // relType is validated above against GraphRelationType's closed enum (letters/underscores
            // only, from a fixed set we control) before being spliced into the query text here.
            String cypher = """
                    UNWIND $rows AS row
                    MATCH (a:Entity {id: row.from})
                    MATCH (b:Entity {id: row.to})
                    MERGE (a)-[r:%s]->(b)
                    ON CREATE SET r.weight = 1, r.firstSeenSource = row.source, r.evidence = row.evidence
                    ON MATCH SET r.weight = coalesce(r.weight, 0) + 1,
                                 r.lastSeenSource = row.source,
                                 r.evidence = CASE WHEN row.evidence <> '' THEN row.evidence ELSE r.evidence END
                    """.formatted(relType);
            run(cypher, Map.of("rows", rows));
        }
    }

    /** Defense in depth: only an exact {@link GraphRelationType} name may ever be spliced into Cypher text. */
    private boolean isSafeRelType(String relType) {
        return GraphRelationType.isValid(relType);
    }
}

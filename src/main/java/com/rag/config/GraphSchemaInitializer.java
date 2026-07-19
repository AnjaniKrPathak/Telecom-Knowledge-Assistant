package com.rag.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ensures the knowledge graph's Neo4j schema exists on startup:
 * <ul>
 *   <li>A uniqueness constraint on {@code Entity.id} and {@code Document.id} (also gives each an
 *       implicit lookup index).</li>
 *   <li>Range indexes on {@code Entity.type} and {@code Entity.value}, since
 *       {@code GraphSearchService} filters/searches on both.</li>
 * </ul>
 * Every statement uses {@code IF NOT EXISTS}, so this is safe to run on every startup (idempotent),
 * matching the pattern {@code FullTextSearchSchemaInitializer} uses for the Postgres side of this
 * project. If Neo4j isn't reachable (e.g. the container isn't up yet), this fails soft — logs a
 * warning and lets the app continue, since the knowledge graph is an additive feature and
 * shouldn't block startup or break RAG query/ingestion, which don't depend on it.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class GraphSchemaInitializer implements ApplicationRunner {

    private final Driver driver;
    private final Neo4jProperties neo4jProperties;
    private final GraphProperties graphProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!graphProperties.isEnabled()) {
            log.info("Knowledge graph disabled (rag.graph.enabled=false) — skipping Neo4j schema setup");
            return;
        }
        try (Session session = driver.session(sessionConfig())) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT entity_id_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE");
                tx.run("CREATE CONSTRAINT document_id_unique IF NOT EXISTS FOR (d:Document) REQUIRE d.id IS UNIQUE");
                tx.run("CREATE INDEX entity_type_index IF NOT EXISTS FOR (e:Entity) ON (e.type)");
                tx.run("CREATE INDEX entity_value_index IF NOT EXISTS FOR (e:Entity) ON (e.value)");
                return null;
            });
            log.info("Knowledge graph ready — Neo4j schema (constraints/indexes) verified at {}", neo4jProperties.getUri());
        } catch (Exception e) {
            log.warn("Could not verify/create Neo4j schema at {} — knowledge graph features will be " +
                            "unavailable until Neo4j is reachable and the app is restarted (or the schema is " +
                            "created manually): {}",
                    neo4jProperties.getUri(), e.getMessage());
        }
    }

    private SessionConfig sessionConfig() {
        return SessionConfig.builder().withDatabase(neo4jProperties.getDatabase()).build();
    }
}

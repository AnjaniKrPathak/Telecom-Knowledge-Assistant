package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "neo4j.*" connection properties from application.yml.
 *
 * neo4j:
 *   uri: bolt://localhost:7687
 *   username: neo4j
 *   password: ragpassword
 *   database: neo4j
 *   max-connection-pool-size: 50
 *   connection-acquisition-timeout-seconds: 30
 * <p>
 * Kept as a plain properties holder (no auto-configured {@code Neo4jClient}/repositories) —
 * {@link Neo4jConfig} uses these to build a single {@code Driver} bean, and every graph
 * read/write goes through {@code com.rag.graph.service.KnowledgeGraphService} with explicit
 * Cypher, the same "direct driver, explicit queries" style already used for Postgres via
 * {@code JdbcTemplate} elsewhere in this project.
 */
@Configuration
@ConfigurationProperties(prefix = "neo4j")
@Data
public class Neo4jProperties {

    /** Bolt connection URI, e.g. bolt://localhost:7687 or neo4j://host:7687 for a cluster. */
    private String uri = "bolt://localhost:7687";

    /** Basic-auth username. Leave blank (together with password) to connect with no auth. */
    private String username = "neo4j";

    /** Basic-auth password. */
    private String password = "";

    /** Neo4j database name (multi-database is a Neo4j 4+ feature; "neo4j" is the default database). */
    private String database = "neo4j";

    /** Driver-side connection pool cap. */
    private int maxConnectionPoolSize = 50;

    /** How long to wait for a pooled connection before failing, in seconds. */
    private int connectionAcquisitionTimeoutSeconds = 30;
}

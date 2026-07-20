package com.rag.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single Neo4j {@link Driver} bean the whole knowledge-graph feature shares
 * (com.rag.graph.*). The driver itself is thread-safe and pools connections internally, so one
 * instance for the whole application is the intended usage (same as one {@code DataSource}/
 * {@code JdbcTemplate} for Postgres elsewhere in this project).
 * <p>
 * Deliberately does NOT fail application startup if Neo4j is unreachable — {@code GraphDatabase.driver(...)}
 * only builds a driver object and doesn't connect eagerly, so a missing/down Neo4j container just means
 * later graph calls fail (and are handled fail-open — see {@code GraphSchemaInitializer} and
 * {@code GraphIngestionService}) rather than the whole app refusing to boot over an optional feature.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class Neo4jConfig {

    private final Neo4jProperties properties;

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver() {
        AuthToken authToken = (properties.getUsername() == null || properties.getUsername().isBlank())
                ? AuthTokens.none()
                : AuthTokens.basic(properties.getUsername(), properties.getPassword());

        Config config = Config.builder()
                .withMaxConnectionPoolSize(properties.getMaxConnectionPoolSize())
                .withConnectionAcquisitionTimeout(
                        properties.getConnectionAcquisitionTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .build();

        log.info("Configuring Neo4j driver for {} (database={})", properties.getUri(), properties.getDatabase());
        return GraphDatabase.driver(properties.getUri(), authToken, config);
    }
}

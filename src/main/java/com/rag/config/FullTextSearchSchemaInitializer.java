package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prepares the existing pgvector table (created by LangChain4j's PgVectorEmbeddingStore) for
 * PostgreSQL full-text search, which powers the keyword leg of hybrid search:
 *
 *   1. Adds a generated `text_search_vector tsvector` column derived from the `text` column.
 *   2. Adds a GIN index on that column so keyword lookups stay fast as the table grows.
 *
 * The primary key column name is discovered from information_schema rather than assumed,
 * since it is an internal implementation detail of the LangChain4j pgvector store and could
 * vary across versions. If anything about the table doesn't match what hybrid search needs
 * (e.g. a missing "text" column), this fails soft: it logs a clear warning and hybrid search
 * transparently falls back to vector-only retrieval instead of the application refusing to start.
 */
@Slf4j
@Component
@Order(0)
public class FullTextSearchSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final HybridSearchProperties hybridProperties;

    @Value("${pgvector.table-name}")
    private String tableName;

    private volatile boolean fullTextSearchAvailable = false;
    private volatile String idColumnName;

    public FullTextSearchSchemaInitializer(JdbcTemplate jdbcTemplate, HybridSearchProperties hybridProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.hybridProperties = hybridProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!hybridProperties.isEnabled()) {
            log.info("Hybrid search disabled (rag.hybrid.enabled=false) — skipping full-text schema setup");
            return;
        }
        if (!isSafeIdentifier(tableName)) {
            log.error("Refusing to run full-text search migration: unsafe table name '{}'", tableName);
            return;
        }

        try {
            this.idColumnName = resolvePrimaryKeyColumn();
            ensureTextColumnExists();
            addGeneratedTsVectorColumn();
            createGinIndex();
            fullTextSearchAvailable = true;
            log.info("Hybrid search ready — full-text index active on table '{}' (id column: '{}')",
                    tableName, idColumnName);
        } catch (Exception e) {
            fullTextSearchAvailable = false;
            log.warn("Could not set up PostgreSQL full-text search on table '{}': {}. " +
                            "Hybrid search will fall back to vector-only retrieval until this is resolved.",
                    tableName, e.getMessage());
        }
    }

    private String resolvePrimaryKeyColumn() {
        List<String> pkColumns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class, tableName);
        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("could not resolve a primary key column for table " + tableName);
        }
        return pkColumns.get(0);
    }

    private void ensureTextColumnExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = 'text'
                """, Integer.class, tableName);
        if (count == null || count == 0) {
            throw new IllegalStateException("expected column 'text' not found on table " + tableName);
        }
    }

    private void addGeneratedTsVectorColumn() {
        String language = hybridProperties.getFtsLanguage().replace("'", "''");
        jdbcTemplate.execute(String.format("""
                ALTER TABLE %s
                ADD COLUMN IF NOT EXISTS text_search_vector tsvector
                GENERATED ALWAYS AS (to_tsvector('%s', coalesce(text, ''))) STORED
                """, tableName, language));
    }

    private void createGinIndex() {
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_text_search ON %s USING GIN (text_search_vector)",
                tableName, tableName));
    }

    private boolean isSafeIdentifier(String identifier) {
        return identifier != null && identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    public boolean isFullTextSearchAvailable() {
        return fullTextSearchAvailable;
    }

    public String getIdColumnName() {
        return idColumnName;
    }
}

package com.rag.search;

import com.rag.config.FullTextSearchSchemaInitializer;
import com.rag.config.HybridSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caches the two corpus-wide statistics BM25's IDF and length-normalization terms need:
 * total document count (N) and average document length (avgdl). Both drift slowly relative
 * to query volume, so they're refreshed on a timer instead of being recomputed on every
 * question — recomputing per-query would mean an extra full-table scan per user question.
 * <p>
 * "Document length" here is {@code length(tsvector)} — the number of distinct lexemes in a
 * chunk. It's a cheap, index-free proxy for true token count (Postgres stores it inline in
 * the tsvector header, so reading it is O(1) per row) and correlates well with true length
 * for chunks produced by a fixed-size splitter. The same proxy is used consistently for both
 * avgdl here and each candidate's own |D| in {@link HybridSearchService}.
 */
@Slf4j
@Component
public class Bm25CorpusStats implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final HybridSearchProperties properties;
    private final FullTextSearchSchemaInitializer schemaInitializer;

    @Value("${pgvector.table-name}")
    private String tableName;

    private volatile long totalDocuments = 0;
    private volatile double averageDocumentLength = 1.0;

    public Bm25CorpusStats(JdbcTemplate jdbcTemplate,
                            HybridSearchProperties properties,
                            FullTextSearchSchemaInitializer schemaInitializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(fixedDelayString = "${rag.hybrid.stats-refresh-interval-ms:300000}")
    public void refresh() {
        if (!properties.isEnabled() || !schemaInitializer.isFullTextSearchAvailable()) {
            return;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    String.format("SELECT count(*) FROM %s WHERE text_search_vector IS NOT NULL", tableName),
                    Long.class);
            Double avgLen = jdbcTemplate.queryForObject(
                    String.format("SELECT avg(length(text_search_vector)) FROM %s WHERE text_search_vector IS NOT NULL", tableName),
                    Double.class);

            this.totalDocuments = count != null ? count : 0;
            this.averageDocumentLength = (avgLen != null && avgLen > 0) ? avgLen : 1.0;
            log.debug("BM25 corpus stats refreshed: N={}, avgdl={}", totalDocuments, averageDocumentLength);
        } catch (Exception e) {
            log.debug("Could not refresh BM25 corpus stats (table may not be populated yet): {}", e.getMessage());
        }
    }

    public long getTotalDocuments() {
        return totalDocuments;
    }

    public double getAverageDocumentLength() {
        return averageDocumentLength;
    }
}

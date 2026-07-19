package com.rag.graph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.GraphProperties;
import com.rag.document.excel.ExcelCatalogFields;
import com.rag.graph.dto.GraphBackfillReport;
import com.rag.graph.extraction.EntityExtractionService;
import com.rag.graph.extraction.RelationshipExtractionService;
import com.rag.graph.model.GraphEntity;
import com.rag.graph.model.GraphRelationship;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * (Re)builds the knowledge graph from every chunk already sitting in the {@code document_embeddings}
 * table — i.e. everything ingested before the knowledge-graph feature existed, or ingested while
 * {@code rag.graph.auto-ingest} was off. Entirely additive/idempotent: every write goes through
 * {@link KnowledgeGraphService}'s MERGE-based upserts, so running this repeatedly (or on top of a
 * graph that already has some auto-ingested data) just reinforces existing nodes/edges rather than
 * duplicating them.
 * <p>
 * Reads {@code text}/{@code metadata} in pages ({@code rag.graph.backfill-batch-size} rows at a
 * time, ordered by physical row order via {@code ctid} — stable enough for a one-off scan, no
 * assumption about a particular primary-key column name needed) rather than loading the whole
 * table at once, since a production-sized knowledge base could be far larger than comfortably
 * fits in memory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBackfillService {

    private final JdbcTemplate jdbcTemplate;
    private final GraphProperties graphProperties;
    private final EntityExtractionService entityExtractionService;
    private final RelationshipExtractionService relationshipExtractionService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${pgvector.table-name}")
    private String tableName;

    public GraphBackfillReport backfillAll() {
        LocalDateTime startedAt = LocalDateTime.now();
        if (!graphProperties.isEnabled()) {
            return GraphBackfillReport.builder()
                    .success(false)
                    .message("Knowledge graph disabled (rag.graph.enabled=false) — nothing to do")
                    .startedAt(startedAt)
                    .completedAt(LocalDateTime.now())
                    .build();
        }

        int batchSize = Math.max(graphProperties.getBackfillBatchSize(), 1);
        int chunksScanned = 0;
        int entitiesWritten = 0;
        int relationshipsWritten = 0;
        int offset = 0;

        try {
            while (true) {
                List<ChunkRow> rows = fetchPage(offset, batchSize);
                if (rows.isEmpty()) {
                    break;
                }

                List<GraphEntity> entityBatch = new ArrayList<>();
                List<GraphRelationship> relationshipBatch = new ArrayList<>();

                for (ChunkRow row : rows) {
                    Map<String, Object> rawMetadata = parseMetadata(row.metadataJson());
                    String source = asString(rawMetadata.get("source"));
                    Map<String, String> catalogFields = ExcelCatalogFields.extractFromRaw(rawMetadata);

                    List<GraphEntity> entities = new ArrayList<>(
                            entityExtractionService.extractFromMetadata(catalogFields, source));
                    entities.addAll(entityExtractionService.extractFromText(row.text(), source));

                    Map<String, GraphEntity> byId = new LinkedHashMap<>();
                    entities.forEach(e -> byId.putIfAbsent(e.id(), e));
                    List<GraphEntity> deduped = new ArrayList<>(byId.values());

                    entityBatch.addAll(deduped);
                    if (deduped.size() >= 2 && graphProperties.isEntityCoOccurrence()) {
                        relationshipBatch.addAll(
                                relationshipExtractionService.extractRelationships(row.text(), deduped, source));
                    }
                    chunksScanned++;
                }

                if (!entityBatch.isEmpty()) {
                    knowledgeGraphService.upsertEntities(entityBatch);
                    knowledgeGraphService.linkEntitiesToDocument(entityBatch);
                    entitiesWritten += entityBatch.size();
                }
                if (!relationshipBatch.isEmpty()) {
                    knowledgeGraphService.upsertRelationships(relationshipBatch);
                    relationshipsWritten += relationshipBatch.size();
                }

                log.info("Graph backfill progress: {} chunks scanned so far (offset {})", chunksScanned, offset);
                offset += batchSize;
            }

            return GraphBackfillReport.builder()
                    .success(true)
                    .message("Backfill complete")
                    .chunksScanned(chunksScanned)
                    .entityMentionsWritten(entitiesWritten)
                    .relationshipsWritten(relationshipsWritten)
                    .startedAt(startedAt)
                    .completedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("Graph backfill failed after {} chunks scanned: {}", chunksScanned, e.getMessage(), e);
            return GraphBackfillReport.builder()
                    .success(false)
                    .message("Backfill failed: " + e.getMessage())
                    .chunksScanned(chunksScanned)
                    .entityMentionsWritten(entitiesWritten)
                    .relationshipsWritten(relationshipsWritten)
                    .startedAt(startedAt)
                    .completedAt(LocalDateTime.now())
                    .build();
        }
    }

    private List<ChunkRow> fetchPage(int offset, int limit) {
        String sql = String.format("""
                SELECT text, metadata FROM %s ORDER BY ctid LIMIT ? OFFSET ?
                """, tableName);
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ChunkRow(rs.getString("text"), rs.getString("metadata")),
                limit, offset);
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Could not parse metadata JSON during graph backfill, skipping row's metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private record ChunkRow(String text, String metadataJson) {
    }
}

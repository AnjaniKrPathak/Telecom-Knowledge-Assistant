package com.rag.graph.service;

import com.rag.config.GraphProperties;
import com.rag.document.excel.ExcelCatalogFields;
import com.rag.graph.extraction.EntityExtractionService;
import com.rag.graph.extraction.RelationshipExtractionService;
import com.rag.graph.model.GraphEntity;
import com.rag.graph.model.GraphRelationship;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges normal document ingestion ({@code IngestionService}) into the knowledge graph: for every
 * batch of freshly-chunked/embedded {@link TextSegment}s, extracts entities + relationships and
 * writes them to Neo4j.
 * <p>
 * Called synchronously right after a document's chunks are embedded and stored in pgvector (see
 * {@code IngestionService}), guarded by {@code rag.graph.enabled} / {@code rag.graph.auto-ingest},
 * and fails open: any exception here is logged and swallowed rather than propagated, so a Neo4j
 * outage never fails a file upload — the graph simply falls behind until
 * {@code POST /api/graph/backfill} is run or the next successful ingestion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphIngestionService {

    private final GraphProperties graphProperties;
    private final EntityExtractionService entityExtractionService;
    private final RelationshipExtractionService relationshipExtractionService;
    private final KnowledgeGraphService knowledgeGraphService;

    public void ingestSegments(List<TextSegment> segments, String source) {
        if (!graphProperties.isEnabled() || !graphProperties.isAutoIngest()) {
            return;
        }
        if (segments == null || segments.isEmpty()) {
            return;
        }
        try {
            List<GraphEntity> allEntities = new ArrayList<>();
            List<GraphRelationship> allRelationships = new ArrayList<>();

            for (TextSegment segment : segments) {
                processSegment(segment, source, allEntities, allRelationships);
            }

            if (!allEntities.isEmpty()) {
                knowledgeGraphService.upsertEntities(allEntities);
                knowledgeGraphService.linkEntitiesToDocument(allEntities);
            }
            if (!allRelationships.isEmpty()) {
                knowledgeGraphService.upsertRelationships(allRelationships);
            }
            log.info("Graph ingestion for '{}': {} entity mention(s), {} relationship(s) written",
                    source, allEntities.size(), allRelationships.size());
        } catch (Exception e) {
            log.warn("Graph ingestion failed for '{}' — RAG ingestion still succeeded; the knowledge " +
                    "graph will be behind for this document until the next successful ingest or a " +
                    "POST /api/graph/backfill run: {}", source, e.getMessage());
        }
    }

    private void processSegment(TextSegment segment, String source,
                                 List<GraphEntity> entityAccumulator, List<GraphRelationship> relationshipAccumulator) {
        Map<String, String> catalogFields = ExcelCatalogFields.extract(segment.metadata());
        List<GraphEntity> entities = new ArrayList<>(entityExtractionService.extractFromMetadata(catalogFields, source));
        entities.addAll(entityExtractionService.extractFromText(segment.text(), source));

        if (entities.isEmpty()) {
            return;
        }

        // De-dup within this one chunk (e.g. a row's ruleId and ruleName both present, or the same
        // narrative pattern matching twice) before accumulating/relating.
        Map<String, GraphEntity> byId = new LinkedHashMap<>();
        entities.forEach(e -> byId.putIfAbsent(e.id(), e));
        List<GraphEntity> deduped = new ArrayList<>(byId.values());

        entityAccumulator.addAll(deduped);

        if (deduped.size() >= 2 && graphProperties.isEntityCoOccurrence()) {
            relationshipAccumulator.addAll(
                    relationshipExtractionService.extractRelationships(segment.text(), deduped, source));
        }
    }
}

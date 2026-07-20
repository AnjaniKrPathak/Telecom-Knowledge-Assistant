package com.rag.graph.service;

import com.rag.config.GraphProperties;
import com.rag.document.excel.ExcelCatalogFields;
import com.rag.graph.extraction.EntityExtractionService;
import com.rag.graph.model.GraphEntity;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Optional bridge from "chunks the vector search just retrieved" to "what does the knowledge
 * graph know about the entities in those chunks" — gated entirely behind
 * {@code rag.graph.query-augmentation-enabled} (off by default) so it can be evaluated for its
 * effect on answer quality before becoming part of every prompt.
 * <p>
 * When enabled: recognizes catalog entities already present in the retrieved chunks, using the
 * exact same two extraction paths ingestion uses (see {@code EntityExtractionService}) — structured
 * catalog metadata (flatOfferingId, ruleId, ...) for Excel-derived chunks, AND the narrative-text
 * regex fallback for DOCX/PDF-derived chunks that carry no catalog metadata at all. Skipping the
 * text fallback here would mean explanation-style answers (which retrieve narrative chunks almost
 * exclusively) never find any entity to look up, even when the retrieved text plainly names one
 * (e.g. "CR-1029", "Rule 71325001") — this is why both paths matter at query time, not just ingestion.
 * <p>
 * Looks up each recognized entity's near dependencies and impact via the existing
 * {@link DependencyAnalysisService}/{@link ImpactAnalysisService}, and turns that into a short
 * prompt block plus a structured summary the API response can surface — e.g. answering "what rules
 * does this discount depend on" even when the retrieved chunk text itself never states that
 * relationship explicitly, because it was inferred from OTHER chunks during ingestion.
 * <p>
 * Fails open: any exception (including "Neo4j is down") is caught and logged, returning
 * {@link GraphContext#EMPTY} rather than failing the RAG turn — this is a nice-to-have on top of
 * retrieval, never a dependency of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphContextEnricherService {

    private final GraphProperties graphProperties;
    private final EntityExtractionService entityExtractionService;
    private final DependencyAnalysisService dependencyAnalysisService;
    private final ImpactAnalysisService impactAnalysisService;

    private static final int EXPANSION_DEPTH = 2;

    public record GraphContext(String promptBlock, List<Map<String, Object>> relatedEntities) {
        public static final GraphContext EMPTY = new GraphContext("", List.of());
    }

    /**
     * @param retrievedSegments the exact chunks retrieval already returned for this query (post
     *                          rerank/feedback-ranking) — both their metadata and their text are
     *                          used, mirroring {@code GraphIngestionService}'s extraction exactly
     */
    public GraphContext enrich(List<TextSegment> retrievedSegments) {
        if (!graphProperties.isEnabled() || !graphProperties.isQueryAugmentationEnabled()) {
            return GraphContext.EMPTY;
        }
        try {
            Map<String, GraphEntity> byId = new LinkedHashMap<>();
            for (TextSegment segment : retrievedSegments) {
                Map<String, String> catalogFields = ExcelCatalogFields.extract(segment.metadata());
                entityExtractionService.extractFromMetadata(catalogFields, "")
                        .forEach(e -> byId.putIfAbsent(e.id(), e));
                entityExtractionService.extractFromText(segment.text(), "")
                        .forEach(e -> byId.putIfAbsent(e.id(), e));
            }
            List<GraphEntity> candidates = byId.values().stream()
                    .limit(Math.max(graphProperties.getQueryAugmentationMaxEntities(), 0))
                    .toList();
            if (candidates.isEmpty()) {
                log.debug("Graph augmentation: no catalog entities recognized in the retrieved chunks — nothing to look up");
                return GraphContext.EMPTY;
            }

            List<Map<String, Object>> related = new ArrayList<>();
            StringBuilder block = new StringBuilder();

            for (GraphEntity entity : candidates) {
                List<Map<String, Object>> dependsOn = dependencyAnalysisService.dependencies(entity.id(), EXPANSION_DEPTH);
                List<Map<String, Object>> impacts = impactAnalysisService.analyzeImpact(entity.id(), EXPANSION_DEPTH);
                if (dependsOn.isEmpty() && impacts.isEmpty()) {
                    continue;
                }

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("entity", entity.id());
                summary.put("dependsOn", dependsOn);
                summary.put("impacts", impacts);
                related.add(summary);

                if (!dependsOn.isEmpty()) {
                    block.append("- ").append(entity.value()).append(" (").append(entity.type())
                            .append(") depends on: ").append(valuesOf(dependsOn)).append("\n");
                }
                if (!impacts.isEmpty()) {
                    block.append("- Changes to ").append(entity.value())
                            .append(" may impact: ").append(valuesOf(impacts)).append("\n");
                }
            }

            if (related.isEmpty()) {
                log.info("Graph augmentation: recognized {} entity candidate(s) ({}) but none had graph " +
                                "relationships — answer is effectively vector-search + reranker only",
                        candidates.size(), candidateIdsOf(candidates));
                return GraphContext.EMPTY;
            }

            String promptBlock = "Related knowledge graph context (relationships inferred across the catalog at " +
                    "ingestion time — may include chunks outside the retrieved context above; verify " +
                    "before relying on this for anything safety- or billing-critical):\n" + block;

            log.info("Graph augmentation: added {} related entit{} to the prompt (out of {} candidate(s) checked): {}",
                    related.size(), related.size() == 1 ? "y" : "ies", candidates.size(), relatedEntityIdsOf(related));

            return new GraphContext(promptBlock, related);
        } catch (Exception e) {
            log.warn("Graph context enrichment failed — answering from vector retrieval alone: {}", e.getMessage());
            return GraphContext.EMPTY;
        }
    }

    private String valuesOf(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> String.valueOf(row.get("value")))
                .limit(5)
                .collect(Collectors.joining(", "));
    }

    private String candidateIdsOf(List<GraphEntity> entities) {
        return entities.stream().map(GraphEntity::id).collect(Collectors.joining(", "));
    }

    private String relatedEntityIdsOf(List<Map<String, Object>> relatedSummaries) {
        return relatedSummaries.stream().map(row -> String.valueOf(row.get("entity"))).collect(Collectors.joining(", "));
    }
}

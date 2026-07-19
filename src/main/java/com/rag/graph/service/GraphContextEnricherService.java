package com.rag.graph.service;

import com.rag.config.GraphProperties;
import com.rag.graph.extraction.EntityExtractionService;
import com.rag.graph.model.GraphEntity;
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
 * When enabled: recognizes catalog entities already present in the retrieved chunks' metadata
 * (no extra vector search needed), looks up each one's near dependencies and impact via the
 * existing {@link DependencyAnalysisService}/{@link ImpactAnalysisService}, and turns that into a
 * short prompt block plus a structured summary the API response can surface — e.g. answering
 * "what rules does this discount depend on" even when the retrieved chunk text itself never
 * states that relationship explicitly, because it was inferred from OTHER chunks during ingestion.
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
     * @param catalogFieldsPerChunk the catalog metadata fields (see {@code ExcelCatalogFields#extract})
     *                              of each chunk retrieval already returned for this query
     */
    public GraphContext enrich(List<Map<String, String>> catalogFieldsPerChunk) {
        if (!graphProperties.isEnabled() || !graphProperties.isQueryAugmentationEnabled()) {
            return GraphContext.EMPTY;
        }
        try {
            Map<String, GraphEntity> byId = new LinkedHashMap<>();
            for (Map<String, String> fields : catalogFieldsPerChunk) {
                entityExtractionService.extractFromMetadata(fields, "")
                        .forEach(e -> byId.putIfAbsent(e.id(), e));
            }
            List<GraphEntity> candidates = byId.values().stream()
                    .limit(Math.max(graphProperties.getQueryAugmentationMaxEntities(), 0))
                    .toList();
            if (candidates.isEmpty()) {
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

            String promptBlock = block.isEmpty()
                    ? ""
                    : "Related knowledge graph context (relationships inferred across the catalog at " +
                      "ingestion time — may include chunks outside the retrieved context above; verify " +
                      "before relying on this for anything safety- or billing-critical):\n" + block;

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
}

package com.rag.controller;

import com.rag.config.GraphProperties;
import com.rag.graph.dto.GraphBackfillReport;
import com.rag.graph.extraction.EntityExtractionService;
import com.rag.graph.service.DependencyAnalysisService;
import com.rag.graph.service.GraphBackfillService;
import com.rag.graph.service.GraphSearchService;
import com.rag.graph.service.ImpactAnalysisService;
import com.rag.graph.service.PathFindingService;
import com.rag.graph.service.RootCauseAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the Neo4j knowledge graph: entity search, neighbor/graph expansion, dependency
 * analysis, root-cause analysis, impact analysis, and path finding over telecom catalog entities
 * (Offerings, Flat Offerings, Rules, Discounts, Bundles, Change Requests, Relations, ...) and the
 * documents/sheets they were extracted from.
 * <p>
 * Entity ids follow {@code Type:normalizedValue}, e.g. {@code Rule:71325001} or
 * {@code ChangeRequest:cr-1029} — resolve a human-typed search term to an id via
 * {@code GET /api/graph/search} first if you don't already have one (e.g. from a prior RAG answer's
 * source metadata).
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "Knowledge Graph", description = "Neo4j-backed entity graph: search, dependency/root-cause/impact analysis, path finding")
public class GraphController {

    private final GraphProperties graphProperties;
    private final GraphSearchService graphSearchService;
    private final DependencyAnalysisService dependencyAnalysisService;
    private final RootCauseAnalysisService rootCauseAnalysisService;
    private final ImpactAnalysisService impactAnalysisService;
    private final PathFindingService pathFindingService;
    private final GraphBackfillService graphBackfillService;

    @GetMapping("/status")
    @Operation(summary = "Current knowledge-graph config (enabled/auto-ingest/depth limits)")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "enabled", graphProperties.isEnabled(),
                "autoIngest", graphProperties.isAutoIngest(),
                "entityCoOccurrence", graphProperties.isEntityCoOccurrence(),
                "maxPathDepth", graphProperties.getMaxPathDepth(),
                "maxDependencyDepth", graphProperties.getMaxDependencyDepth(),
                "queryAugmentationEnabled", graphProperties.isQueryAugmentationEnabled()
        ));
    }

    @PostMapping("/backfill")
    @Operation(summary = "Rebuild the knowledge graph from every chunk already in the vector store " +
            "(entity extraction + relationship inference). Safe to re-run — every write is a MERGE-based upsert.")
    public ResponseEntity<GraphBackfillReport> backfill() {
        return ResponseEntity.ok(graphBackfillService.backfillAll());
    }

    @GetMapping("/search")
    @Operation(summary = "Find entities by (partial, case-insensitive) name/value or id, e.g. \"71325001\" or \"CR-1029\"")
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(graphSearchService.searchEntities(query, limit));
    }

    @GetMapping("/entities/{entityId}/neighbors")
    @Operation(summary = "Every entity connected to this one within N hops (any relationship type/direction)")
    public ResponseEntity<List<Map<String, Object>>> neighbors(
            @PathVariable("entityId") String entityId,
            @RequestParam(value = "depth", defaultValue = "1") int depth) {
        return ResponseEntity.ok(graphSearchService.neighbors(entityId, depth));
    }

    @GetMapping("/entities/{entityId}/documents")
    @Operation(summary = "Every document/sheet this entity was mentioned in")
    public ResponseEntity<List<Map<String, Object>>> documents(@PathVariable("entityId") String entityId) {
        return ResponseEntity.ok(graphSearchService.documentsFor(entityId));
    }

    @GetMapping("/entities/{entityId}/dependencies")
    @Operation(summary = "What this entity depends on, transitively (DEPENDS_ON edges), nearest hop first")
    public ResponseEntity<List<Map<String, Object>>> dependencies(
            @PathVariable("entityId") String entityId,
            @RequestParam(value = "depth", defaultValue = "3") int depth) {
        return ResponseEntity.ok(dependencyAnalysisService.dependencies(entityId, depth));
    }

    @GetMapping("/entities/{entityId}/dependents")
    @Operation(summary = "What depends on this entity, transitively (DEPENDS_ON edges), nearest hop first")
    public ResponseEntity<List<Map<String, Object>>> dependents(
            @PathVariable("entityId") String entityId,
            @RequestParam(value = "depth", defaultValue = "3") int depth) {
        return ResponseEntity.ok(dependencyAnalysisService.dependents(entityId, depth));
    }

    @GetMapping("/entities/{entityId}/root-cause")
    @Operation(summary = "Candidate root causes upstream of a \"symptom\" entity, ranked most-likely-root first " +
            "(fewest further upstream dependencies, then farthest from the symptom)")
    public ResponseEntity<List<Map<String, Object>>> rootCause(
            @PathVariable("entityId") String entityId,
            @RequestParam(value = "depth", defaultValue = "4") int depth) {
        return ResponseEntity.ok(rootCauseAnalysisService.findRootCauses(entityId, depth));
    }

    @GetMapping("/entities/{entityId}/impact")
    @Operation(summary = "Everything downstream that could be affected if this entity changes, grouped by hop distance and source document(s)")
    public ResponseEntity<List<Map<String, Object>>> impact(
            @PathVariable("entityId") String entityId,
            @RequestParam(value = "depth", defaultValue = "4") int depth) {
        return ResponseEntity.ok(impactAnalysisService.analyzeImpact(entityId, depth));
    }

    @GetMapping("/path")
    @Operation(summary = "Shortest path between two entities (any relationship type/direction)")
    public ResponseEntity<?> shortestPath(
            @RequestParam("from") String fromId,
            @RequestParam("to") String toId) {
        return pathFindingService.shortestPath(fromId, toId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "found", false,
                        "message", "No path found between '" + fromId + "' and '" + toId + "' within the configured depth limit")));
    }

    @GetMapping("/paths")
    @Operation(summary = "Every distinct shortest path between two entities (there can be more than one at the same minimum length)")
    public ResponseEntity<List<Map<String, Object>>> allPaths(
            @RequestParam("from") String fromId,
            @RequestParam("to") String toId,
            @RequestParam(value = "maxDepth", defaultValue = "6") int maxDepth) {
        return ResponseEntity.ok(pathFindingService.allShortestPaths(fromId, toId, maxDepth));
    }

    /** Handy helper for clients that only have a raw type/value pair and want the exact graph entity id. */
    @GetMapping("/entity-id")
    @Operation(summary = "Compute the graph entity id for a given type + value, e.g. type=Rule&value=71325001 -> Rule:71325001")
    public ResponseEntity<Map<String, String>> entityId(
            @RequestParam("type") String type,
            @RequestParam("value") String value) {
        return ResponseEntity.ok(Map.of("id", EntityExtractionService.buildId(type, value)));
    }
}

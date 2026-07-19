package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.graph.*" properties from application.yml — controls the Neo4j knowledge-graph
 * feature: entity/relationship extraction at ingestion time, and graph search / dependency /
 * root-cause / impact / path-finding analysis at query time.
 *
 * rag:
 *   graph:
 *     enabled: true
 *     auto-ingest: true
 *     entity-co-occurrence: true
 *     max-path-depth: 6
 *     max-dependency-depth: 4
 *     backfill-batch-size: 500
 *     query-augmentation-enabled: false
 *     query-augmentation-max-entities: 5
 */
@Configuration
@ConfigurationProperties(prefix = "rag.graph")
@Data
public class GraphProperties {

    /** Master switch. When false, every graph service/controller endpoint fails soft (no-op / empty result). */
    private boolean enabled = true;

    /**
     * When true, {@code IngestionService} pushes newly-ingested chunks through
     * {@code GraphIngestionService} automatically (extract entities/relationships, write to Neo4j)
     * right after they're embedded and stored in pgvector. When false, the graph only reflects
     * whatever a manual {@code POST /api/graph/backfill} run produced.
     */
    private boolean autoIngest = true;

    /**
     * When true, entities extracted from the same chunk/row are linked to each other (co-occurrence,
     * or a keyword-inferred relationship like DEPENDS_ON/TRIGGERS — see RelationshipExtractionService).
     * When false, only entities themselves (and their APPEARS_IN links to documents) are written.
     */
    private boolean entityCoOccurrence = true;

    /** Hop ceiling for graph search neighbor expansion and shortest/all-paths queries. */
    private int maxPathDepth = 6;

    /** Hop ceiling for dependency / root-cause / impact traversals. */
    private int maxDependencyDepth = 4;

    /** Rows read per page when {@code POST /api/graph/backfill} scans the document_embeddings table. */
    private int backfillBatchSize = 500;

    /**
     * When true, {@code RagQueryService} adds a short "related knowledge graph context" block
     * (dependencies/impact of entities recognized in the retrieved chunks) to the prompt before
     * calling the LLM. Off by default so the effect on answer quality can be evaluated deliberately
     * rather than changing every existing answer's prompt shape.
     */
    private boolean queryAugmentationEnabled = false;

    /** Cap on how many recognized entities get graph-expanded per query, to bound prompt size and latency. */
    private int queryAugmentationMaxEntities = 5;
}

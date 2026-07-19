package com.rag.graph.model;

/**
 * A candidate knowledge-graph node extracted from an ingested chunk — a telecom catalog/business
 * identifier (Offering, Flat Offering, Change Request, Bundle, Tariff, Discount, Rule, Relation,
 * Price Key, ...) or a generically auto-detected *Id/*Name/*Key/*Code field, plus (rarely) an
 * identifier recognized directly in narrative text (see {@code EntityExtractionService}).
 * <p>
 * All entities are stored in Neo4j under a single {@code :Entity} label with a {@code type}
 * property (rather than a dynamic Neo4j label per type) — see {@code KnowledgeGraphService} for
 * why: Neo4j/Cypher doesn't support parameterized labels without the optional APOC plugin, and
 * this project intentionally avoids requiring APOC in the Docker image. Filtering/grouping by
 * {@code type} works identically to filtering by label for every query in this feature.
 *
 * @param id     stable, deduplication-safe identifier — {@code type + ":" + normalizedValue}
 *               (see {@code EntityExtractionService#buildId}), so the same business entity
 *               referenced from different chunks/sheets/documents merges onto one graph node
 * @param type   canonical entity type, e.g. "Offering", "Rule", "ChangeRequest", "Discount"
 * @param value  the human-readable value as it appeared in the source (original casing/spacing)
 * @param source the document/sheet/URL this mention came from (chunk's {@code source} metadata)
 */
public record GraphEntity(String id, String type, String value, String source) {
}

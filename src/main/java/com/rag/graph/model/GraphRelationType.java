package com.rag.graph.model;

/**
 * The closed whitelist of relationship types the knowledge graph writes. Neo4j/Cypher can't
 * parameterize a relationship type in a query ({@code MERGE (a)-[r:$type]->(b)} isn't legal
 * Cypher), so relationship types are always embedded directly into the Cypher text
 * ({@code KnowledgeGraphService#upsertRelationships}) — this enum, plus a same-named-value
 * validation check, is what keeps that safe: only these exact literal strings are ever spliced
 * into a query, never an arbitrary/user-supplied value.
 */
public enum GraphRelationType {
    /** A depends on B — inferred from "depends on"/"requires"/"needs" in the chunk text. */
    DEPENDS_ON,
    /** A triggers/causes B — inferred from "triggers"/"causes"/"leads to". */
    TRIGGERS,
    /** A replaces/supersedes B — inferred from "replaces"/"supersedes". */
    REPLACES,
    /** A overrides B — inferred from "overrides". */
    OVERRIDES,
    /** A is explicitly related to/mapped to B — inferred from "maps to"/"relates to"/"associated with". */
    RELATES_TO,
    /** A and B simply appeared in the same chunk/row with no directional keyword found. */
    RELATED_TO,
    /** An Entity node was mentioned in a Document node (structural, not business-domain). */
    APPEARS_IN;

    /** @return true if {@code candidate} exactly matches one of this enum's names (case-sensitive). */
    public static boolean isValid(String candidate) {
        if (candidate == null) {
            return false;
        }
        for (GraphRelationType type : values()) {
            if (type.name().equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}

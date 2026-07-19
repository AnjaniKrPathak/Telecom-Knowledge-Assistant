package com.rag.graph.model;

/**
 * A candidate directed edge between two {@link GraphEntity} nodes, inferred by
 * {@code RelationshipExtractionService} from two entities co-occurring in the same chunk/row,
 * optionally refined by a keyword found in that chunk's text (see {@link GraphRelationType}).
 * <p>
 * This is a heuristic, not real NLP relation extraction: when a directional keyword ("depends on",
 * "triggers", "replaces", ...) is found anywhere in the chunk, every entity pair in that chunk is
 * linked with that relationship type in extraction order (first-mentioned entity -&gt; second).
 * That's precise enough to be useful for a telecom catalog/rules corpus, where such phrases
 * usually do describe the two nearest identifiers, but it's not a substitute for a dependency
 * genuinely declared row-by-row in a spreadsheet — treat root-cause/impact results as leads to
 * verify, not ground truth.
 *
 * @param fromId   source entity id
 * @param toId     target entity id
 * @param relType  one of {@link GraphRelationType}'s names (validated before being embedded in Cypher —
 *                 see {@code KnowledgeGraphService#isSafeRelType})
 * @param source   the document/sheet/URL this relationship was inferred from
 * @param evidence a short snippet of the text that suggested this relationship (for the
 *                 directional types), or null for plain co-occurrence
 */
public record GraphRelationship(String fromId, String toId, String relType, String source, String evidence) {
}

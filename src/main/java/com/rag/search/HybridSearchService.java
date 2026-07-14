package com.rag.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.FullTextSearchSchemaInitializer;
import com.rag.config.HybridSearchProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval: combines dense vector similarity search (pgvector, via LangChain4j's
 * EmbeddingStore) with sparse keyword search - real Okapi BM25 over PostgreSQL's built-in
 * full-text search (tsvector/GIN), not the default ts_rank_cd ranking - then fuses
 * the two ranked lists using Reciprocal Rank Fusion (RRF).
 * <p>
 * Vector search alone misses exact-match terms it wasn't trained to weigh heavily - product
 * codes, error codes, ticket IDs, acronyms, part numbers. Keyword search alone misses
 * paraphrases and synonyms. Combining both consistently outperforms either one individually
 * for RAG retrieval quality.
 * <p>
 * Why not ts_rank_cd? Postgres's built-in ranking functions weigh term
 * frequency and proximity, but have no real inverse-document-frequency term and only a crude
 * length normalization - so a rare, highly diagnostic term (an error code) doesn't get
 * weighted any higher than a common one. BM25 fixes that with a proper IDF term and tunable
 * length normalization, which is why it's the standard lexical-ranking algorithm.
 * <p>
 * How BM25 is computed here, without any new Postgres extension: the GIN index built
 * by FullTextSearchSchemaInitializer is used for fast candidate recall (an
 * indexed @@ match, coarsely pre-ordered). For that shortlist, exact per-document term
 * frequencies are read straight out of each row's tsvector text representation (see
 * TsVectorParser) - no extra per-document queries. Corpus-wide statistics (N, avgdl)
 * are cached and refreshed periodically by Bm25CorpusStats. Per-query-term document
 * frequency is a single indexed COUNT(*) per distinct term. Bm25Scorer then
 * combines all of this into the standard BM25 formula.
 * <p>
 * RRF (Cormack et al., 2009) is used for the final fusion instead of a weighted score blend
 * because cosine similarity and BM25 scores live on incomparable scales - RRF only cares
 * about each result's rank within its own list, so no score normalization is needed:
 * <pre>
 *     score(doc) = sum of 1 / (k + rank_in_list)
 * </pre>
 * If the full-text index isn't available for any reason (e.g. schema migration failed), this
 * service transparently degrades to vector-only ranking rather than failing the query.
 */
@Slf4j
@Service
public class HybridSearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;
    private final HybridSearchProperties properties;
    private final FullTextSearchSchemaInitializer schemaInitializer;
    private final Bm25CorpusStats corpusStats;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${pgvector.table-name}")
    private String tableName;

    public HybridSearchService(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore,
                                JdbcTemplate jdbcTemplate,
                                HybridSearchProperties properties,
                                FullTextSearchSchemaInitializer schemaInitializer,
                                Bm25CorpusStats corpusStats) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
        this.corpusStats = corpusStats;
    }

    /**
     * Retrieves the top {@code topK} chunks for {@code question} using hybrid search
     * (or vector-only search if hybrid search isn't currently available).
     */
    public List<RetrievedChunk> search(String question, int topK) {
        int candidatePoolSize = Math.max(topK * Math.max(properties.getCandidateMultiplier(), 1), topK);

        Embedding questionEmbedding = embeddingModel.embed(question).content();
        List<EmbeddingMatch<TextSegment>> vectorMatches = vectorSearch(questionEmbedding, candidatePoolSize);

        boolean useKeywordLeg = properties.isEnabled() && schemaInitializer.isFullTextSearchAvailable();
        List<KeywordHit> keywordHits = useKeywordLeg ? bm25Search(question, candidatePoolSize) : List.of();

        if (!useKeywordLeg) {
            log.debug("Keyword leg unavailable for this query - using vector-only ranking");
        }
        log.info("Hybrid retrieval: {} vector candidates, {} BM25 candidates", vectorMatches.size(), keywordHits.size());

        return fuse(vectorMatches, keywordHits, topK);
    }

    // -- Vector leg --------------------------------------------------------------
    private List<EmbeddingMatch<TextSegment>> vectorSearch(Embedding questionEmbedding, int limit) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(limit)
                .minScore(properties.getMinVectorScore())
                .build();
        return embeddingStore.search(request).matches();
    }

    // -- Keyword leg (BM25) --------------------------------------------------------------
    private List<KeywordHit> bm25Search(String question, int limit) {
        String language = properties.getFtsLanguage();

        // 1. Tokenize the question exactly the way documents were indexed, so lexemes line up.
        Map<String, Integer> queryTerms = tokenize(question, language);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // 2. Document frequency per query term: one indexed COUNT(*) per distinct term.
        Map<String, Integer> documentFrequencies = documentFrequencies(queryTerms.keySet());

        // 3. Candidate shortlist via the GIN index (coarse recall only - final ranking is BM25 below).
        String idColumn = schemaInitializer.getIdColumnName();
        String sql = String.format("""
                SELECT %s AS chunk_id, text, metadata, text_search_vector::text AS tsv,
                       length(text_search_vector) AS doc_length
                FROM %s
                WHERE text_search_vector @@ plainto_tsquery('%s', ?)
                ORDER BY ts_rank_cd(text_search_vector, plainto_tsquery('%s', ?)) DESC
                LIMIT ?
                """, idColumn, tableName, language, language);

        Bm25Scorer scorer = new Bm25Scorer(
                properties.getBm25K1(),
                properties.getBm25B(),
                corpusStats.getTotalDocuments(),
                corpusStats.getAverageDocumentLength());

        try {
            List<KeywordHit> hits = jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
                        Map<String, Integer> documentTermFrequencies = TsVectorParser.parse(rs.getString("tsv"));
                        int documentLength = rs.getInt("doc_length");
                        double bm25 = scorer.score(documentFrequencies, documentTermFrequencies, documentLength);
                        return new KeywordHit(
                                rs.getString("chunk_id"),
                                rs.getString("text"),
                                asString(metadata.get("source")),
                                asString(metadata.get("type")),
                                asString(metadata.get("workbook")),
                                asString(metadata.get("sheet")),
                                asString(metadata.get("rowStart")),
                                asString(metadata.get("rowEnd")),
                                bm25);
                    },
                    question, question, limit);

            // 4. Re-rank the shortlist by true BM25 - the SQL ORDER BY above was only for recall.
            return hits.stream()
                    .filter(hit -> hit.bm25Score() > 0)
                    .sorted(Comparator.comparingDouble(KeywordHit::bm25Score).reversed())
                    .toList();
        } catch (DataAccessException e) {
            log.warn("BM25 keyword search failed, continuing with vector-only results: {}", e.getMessage());
            return List.of();
        }
    }

    /** Runs the question through the same tokenizer/stemmer used at indexing time. */
    private Map<String, Integer> tokenize(String text, String language) {
        try {
            String tsv = jdbcTemplate.queryForObject(
                    "SELECT to_tsvector(?::regconfig, ?)::text", String.class, language, text);
            return TsVectorParser.parse(tsv);
        } catch (DataAccessException e) {
            log.warn("Could not tokenize query for BM25 search: {}", e.getMessage());
            return Map.of();
        }
    }

    /** df(t) per query lexeme, via one GIN-indexed COUNT(*) per distinct term. */
    private Map<String, Integer> documentFrequencies(java.util.Set<String> lexemes) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String sql = String.format("SELECT count(*) FROM %s WHERE text_search_vector @@ ?::tsquery", tableName);
        for (String lexeme : lexemes) {
            try {
                // A quoted literal in tsquery text-input syntax matches exactly that lexeme,
                // bypassing re-stemming - correct since `lexeme` is already a stemmed form.
                String tsqueryLiteral = "'" + lexeme.replace("'", "''") + "'";
                Integer df = jdbcTemplate.queryForObject(sql, Integer.class, tsqueryLiteral);
                result.put(lexeme, df != null ? df : 0);
            } catch (DataAccessException e) {
                log.debug("Could not compute document frequency for term '{}': {}", lexeme, e.getMessage());
                result.put(lexeme, 1); // neutral fallback so this term is scored, not dropped or over-weighted
            }
        }
        return result;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Could not parse metadata JSON, ignoring: {}", e.getMessage());
            return Map.of();
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    // -- Fusion (Reciprocal Rank Fusion) --------------------------------------------------------------
    private List<RetrievedChunk> fuse(List<EmbeddingMatch<TextSegment>> vectorMatches,
                                       List<KeywordHit> keywordHits,
                                       int topK) {
        int k = properties.getRrfK();
        Map<String, RetrievedChunk.Builder> builders = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorMatches.size(); rank++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(rank);
            String id = match.embeddingId();
            double rrfContribution = 1.0 / (k + rank + 1);
            TextSegment segment = match.embedded();
            builders.computeIfAbsent(id, RetrievedChunk.Builder::new)
                    .withVector(
                            segment.text(),
                            segment.metadata().getString("source"),
                            segment.metadata().getString("type"),
                            segment.metadata().getString("workbook"),
                            segment.metadata().getString("sheet"),
                            segment.metadata().getString("rowStart"),
                            segment.metadata().getString("rowEnd"),
                            match.score(),
                            rrfContribution);
        }

        for (int rank = 0; rank < keywordHits.size(); rank++) {
            KeywordHit hit = keywordHits.get(rank);
            double rrfContribution = 1.0 / (k + rank + 1);
            builders.computeIfAbsent(hit.id(), RetrievedChunk.Builder::new)
                    .withKeyword(hit.text(), hit.source(), hit.type(),
                            hit.workbook(), hit.sheet(), hit.rowStart(), hit.rowEnd(),
                            hit.bm25Score(), rrfContribution);
        }

        return builders.values().stream()
                .map(RetrievedChunk.Builder::build)
                .sorted(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed())
                .limit(topK)
                .toList();
    }

    private record KeywordHit(String id, String text, String source, String type,
                               String workbook, String sheet, String rowStart, String rowEnd,
                               double bm25Score) {}
}

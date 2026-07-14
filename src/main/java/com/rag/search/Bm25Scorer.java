package com.rag.search;

import java.util.Map;

/**
 * Computes the Okapi BM25 relevance score for a single document against a set of query
 * terms, given precomputed statistics:
 *
 * <pre>
 *   score(D,Q) = Σ_t idf(t) · tf(t,D)·(k1+1) / (tf(t,D) + k1·(1 - b + b·|D|/avgdl))
 *   idf(t)     = ln( 1 + (N - df(t) + 0.5) / (df(t) + 0.5) )
 * </pre>
 *
 * where N is the total corpus size, df(t) is how many documents contain term t, tf(t,D) is
 * how many times t occurs in D, |D| is D's length, and avgdl is the corpus's average
 * document length. The "+1" inside idf keeps it non-negative even for terms that appear in
 * more than half the corpus (the classic Robertson-Sparck Jones formula can go negative there).
 */
final class Bm25Scorer {

    private final double k1;
    private final double b;
    private final long totalDocuments;
    private final double averageDocumentLength;

    Bm25Scorer(double k1, double b, long totalDocuments, double averageDocumentLength) {
        this.k1 = k1;
        this.b = b;
        this.totalDocuments = Math.max(totalDocuments, 1);
        this.averageDocumentLength = Math.max(averageDocumentLength, 1e-6);
    }

    /**
     * @param queryTermDocumentFrequencies query lexeme -> df(t) (documents in the whole corpus containing it)
     * @param documentTermFrequencies      this document's lexeme -> tf(t,D)
     * @param documentLength               this document's length |D|
     */
    double score(Map<String, Integer> queryTermDocumentFrequencies,
                 Map<String, Integer> documentTermFrequencies,
                 int documentLength) {
        double score = 0.0;
        for (Map.Entry<String, Integer> entry : queryTermDocumentFrequencies.entrySet()) {
            String term = entry.getKey();
            int df = entry.getValue();
            int tf = documentTermFrequencies.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            double idf = Math.log(1.0 + (totalDocuments - df + 0.5) / (df + 0.5));
            double numerator = tf * (k1 + 1);
            double denominator = tf + k1 * (1 - b + b * (documentLength / averageDocumentLength));
            score += idf * (numerator / denominator);
        }
        return score;
    }
}

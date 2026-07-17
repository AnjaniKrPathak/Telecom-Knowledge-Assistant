package com.rag.rerank;

import com.rag.config.RerankerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reranks by calling a locally self-hosted Hugging Face Text-Embeddings-Inference (TEI) server —
 * no API key, no outbound network call beyond localhost/your own network, in line with this
 * project's "fully local inference" requirement.
 * <p>
 * Run one with, e.g.:
 * <pre>
 * docker run -d --name hf-reranker -p 8081:80 \
 *   ghcr.io/huggingface/text-embeddings-inference:cpu-1.5 \
 *   --model-id BAAI/bge-reranker-base
 * </pre>
 * (swap the image for a CUDA tag if you have a GPU). TEI's {@code POST /rerank} endpoint scores
 * every candidate against the query in a single request and returns them already sorted, so this
 * is one HTTP call total per question — no per-candidate round trips like the LLM-based fallback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HuggingFaceRerankerProvider implements RerankerProvider {

    @Qualifier("rerankerRestTemplate")
    private final RestTemplate rerankerRestTemplate;
    private final RerankerProperties properties;

    @Override
    public String id() {
        return "huggingface";
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topN) {
        String url = properties.getHuggingface().getBaseUrl() + "/rerank";
        log.info("Calling Hugging Face reranker: {}", url);
        log.info("Query: {}", query);
        log.info("Candidates: {}", candidates.size());

        Map<String, Object> body = Map.of(
                "query", query,
                "texts", candidates.stream().map(RerankCandidate::text).toList(),
                "raw_scores", false
        );
        log.info("HF response: {}", Arrays.toString(body.entrySet().toArray()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        TeiRerankResult[] results = rerankerRestTemplate.postForObject(url, request, TeiRerankResult[].class);
        if (results == null) {
            throw new IllegalStateException("Empty response from Hugging Face reranker at " + url);
        }

        return Arrays.stream(results)
                .map(r -> new RerankedCandidate(candidates.get(r.index()).id(), r.score()))
                .toList();
    }

    /** One entry of TEI's /rerank response: {"index": <position in the request's "texts" list>, "score": <float>}. */
    private record TeiRerankResult(int index, double score) {}
}

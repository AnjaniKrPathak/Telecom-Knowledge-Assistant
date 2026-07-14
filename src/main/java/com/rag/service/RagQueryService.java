package com.rag.service;

import com.rag.search.HybridSearchService;
import com.rag.search.RetrievedChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatLanguageModel chatLanguageModel;
    private final HybridSearchService hybridSearchService;
    private final QueryCacheService queryCacheService;

    @Value("${rag.max-results}")
    private int maxResults;

    public RagResponse query(String question) {
        log.info("RAG query: {}", question);

        // 0. Cache check — if this same question (normalized) was answered within the TTL
        // window, return that answer directly and skip retrieval + LLM generation entirely.
        Optional<RagResponse> cached = queryCacheService.get(question);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 1+2. Hybrid retrieval: vector similarity (pgvector) + keyword search (Postgres
        // full-text), fused via Reciprocal Rank Fusion. Falls back to vector-only under the
        // hood if the full-text index isn't available.
        List<RetrievedChunk> chunks = hybridSearchService.search(question, maxResults);
        log.info("Retrieved {} relevant chunks", chunks.size());

        if (chunks.isEmpty()) {
            return new RagResponse(
                    question,
                    "I don't have enough information in the knowledge base to answer this question.",
                    List.of()
            );
        }

        // 3. Build context from retrieved chunks
        String context = chunks.stream()
                .map(RetrievedChunk::text)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. Build RAG prompt
        String prompt = buildPrompt(question, context);

        // 5. Generate answer using llama3
        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
        String answer = response.content().text();
        log.info("Generated answer (length={})", answer.length());

        // 6. Build source references
        List<SourceReference> sources = chunks.stream()
                .map(chunk -> new SourceReference(
                        chunk.source(),
                        chunk.type(),
                        chunk.excelLocation(),
                        chunk.combinedScore(),
                        chunk.matchType(),
                        chunk.text().substring(0, Math.min(200, chunk.text().length())) + "..."
                ))
                .collect(Collectors.toList());

        RagResponse ragResponse = new RagResponse(question, answer, sources);
        queryCacheService.put(question, ragResponse);
        return ragResponse;
    }

    private String buildPrompt(String question, String context) {
        return """
                You are a helpful assistant. Use ONLY the context below to answer the question.
                If the answer is not in the context, say "I don't know based on the provided documents."
                
                Context:
                %s
                
                Question: %s
                
                Answer:
                """.formatted(context, question);
    }

    // ── Response DTOs ────────────────────────────────────────────────────────
    public record RagResponse(String question, String answer, List<SourceReference> sources) {}

    public record SourceReference(String source, String type, String location, double score,
                                   String matchType, String excerpt) {}
}

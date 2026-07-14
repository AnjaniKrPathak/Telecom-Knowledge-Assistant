package com.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.max-results}")
    private int maxResults;

    public RagResponse query(String question) {
        log.info("RAG query: {}", question);

        // 1. Embed the question
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Retrieve top-k relevant chunks from pgvector
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(0.5)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        log.info("Retrieved {} relevant chunks", matches.size());

        if (matches.isEmpty()) {
            return new RagResponse(
                    question,
                    "I don't have enough information in the knowledge base to answer this question.",
                    List.of()
            );
        }

        // 3. Build context from retrieved chunks
        String context = matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. Build RAG prompt
        String prompt = buildPrompt(question, context);

        // 5. Generate answer using llama3
        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
        String answer = response.content().text();
        log.info("Generated answer (length={})", answer.length());

        // 6. Build source references
        List<SourceReference> sources = matches.stream()
                .map(match -> new SourceReference(
                        match.embedded().metadata().getString("source"),
                        match.embedded().metadata().getString("type"),
                        match.score(),
                        match.embedded().text().substring(0, Math.min(200, match.embedded().text().length())) + "..."
                ))
                .collect(Collectors.toList());

        return new RagResponse(question, answer, sources);
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

    public record SourceReference(String source, String type, double score, String excerpt) {}
}

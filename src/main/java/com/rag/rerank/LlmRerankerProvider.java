package com.rag.rerank;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reranks by asking the local Ollama chat model (llama3.2) to score each passage 0-10 against the
 * question — one LLM call per candidate, run in parallel. Needs no extra infrastructure beyond
 * what's already running, but N candidates means N LLM calls, so it's noticeably slower and less
 * consistent than a purpose-built cross-encoder. Kept as {@code rag.reranker.provider: llm} — a
 * fallback for setups that don't want to run a separate Hugging Face TEI container.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRerankerProvider implements RerankerProvider {

    private static final Pattern SCORE_PATTERN = Pattern.compile("\\d+");
    private static final int MAX_PASSAGE_CHARS = 800;

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topN) {
        List<CompletableFuture<RerankedCandidate>> futures = candidates.stream()
                .map(candidate -> CompletableFuture.supplyAsync(() ->
                        new RerankedCandidate(candidate.id(), scoreRelevance(query, candidate.text()))))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingDouble(RerankedCandidate::score).reversed())
                .toList();
    }

    private double scoreRelevance(String query, String passage) {
        String prompt = """
                Rate how relevant the PASSAGE is to answering the QUESTION.
                Respond with ONLY a single integer from 0 to 10 (10 = highly relevant, 0 = irrelevant).
                No explanation, no words, just the number.
                
                QUESTION: %s
                
                PASSAGE: %s
                
                SCORE:
                """.formatted(query, truncate(passage, MAX_PASSAGE_CHARS));

        try {
            Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
            return parseScore(response.content().text());
        } catch (Exception e) {
            log.warn("LLM reranker scoring failed, defaulting to 0: {}", e.getMessage());
            return 0;
        }
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private int parseScore(String response) {
        Matcher m = SCORE_PATTERN.matcher(response);
        if (m.find()) {
            return Math.max(0, Math.min(10, Integer.parseInt(m.group())));
        }
        return 0;
    }
}

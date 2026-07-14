package com.rag.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RerankerService {

    private final ChatLanguageModel chatLanguageModel;

    private static final Pattern SCORE_PATTERN = Pattern.compile("\\d+");
    private static final int MAX_PASSAGE_CHARS = 800;

    public List<EmbeddingMatch<TextSegment>> rerank(String query,
                                                    List<EmbeddingMatch<TextSegment>> candidates,
                                                    int topN) {
        if (candidates.size() <= topN) {
            return candidates;
        }

        log.info("Reranking {} candidates down to top {}", candidates.size(), topN);

        List<CompletableFuture<ScoredMatch>> futures = candidates.stream()
                .map(match -> CompletableFuture.supplyAsync(() ->
                        new ScoredMatch(match, scoreRelevance(query, match.embedded().text()))))
                .toList();

        List<ScoredMatch> scored = futures.stream()
                .map(CompletableFuture::join)
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .collect(Collectors.toList());

        return scored.stream()
                .limit(topN)
                .map(ScoredMatch::match)
                .collect(Collectors.toList());
    }

    private int scoreRelevance(String query, String passage) {
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
            log.warn("Reranker scoring failed, defaulting to 0: {}", e.getMessage());
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

    private record ScoredMatch(EmbeddingMatch<TextSegment> match, int score) {}
}

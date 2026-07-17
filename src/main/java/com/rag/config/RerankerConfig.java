package com.rag.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Separate, short-timeout RestTemplate for calling the local Hugging Face reranker service —
 * deliberately NOT the same bean webex/ollama clients use, since a stalled reranker should fail
 * fast (rag.reranker.fail-open falls back to plain vector order) rather than hang the whole query.
 * <p>
 * Bean name "rerankerRestTemplate" is relied on for autowiring (Spring falls back to matching the
 * injection point's name when multiple RestTemplate beans exist) — same convention as WebexRestConfig.
 */
@Configuration
public class RerankerConfig {

    @Bean
    public RestTemplate rerankerRestTemplate(RestTemplateBuilder builder, RerankerProperties properties) {
        int timeoutMs = properties.getHuggingface().getTimeoutMs();
        return builder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }
}

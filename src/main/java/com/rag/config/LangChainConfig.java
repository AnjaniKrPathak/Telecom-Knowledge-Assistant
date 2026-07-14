package com.rag.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Builds the LangChain4j chat model, embedding model, and pgvector store.
 * <p>
 * The chat model's provider is switchable via {@code llm.provider}, normally set per Spring
 * profile (see {@code application-llama3.yml}, {@code application-kimi.yml},
 * {@code application-gpt.yml}, {@code application-openai.yml}). Run with e.g.
 * {@code --spring.profiles.active=kimi} or {@code SPRING_PROFILES_ACTIVE=kimi} to switch.
 * <p>
 * "ollama" builds a local {@link OllamaChatModel}; every other provider (openai, gpt, kimi)
 * builds an {@link OpenAiChatModel} pointed at that provider's base URL — Kimi/Moonshot and
 * most other hosted LLM APIs expose an OpenAI-compatible {@code /chat/completions} endpoint,
 * so one client class covers all of them; only the base URL, model name, and API key differ.
 * <p>
 * The embedding model always stays on Ollama (nomic-embed-text) regardless of the chat
 * provider, since pgvector's column dimension is fixed at ingestion time — switching
 * embedding providers would require re-ingesting every document.
 */
@Slf4j
@Configuration
public class LangChainConfig {

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Value("${pgvector.dimension}")
    private int vectorDimension;

    @Value("${pgvector.table-name}")
    private String tableName;

    // ── Chat model provider selection ────────────────────────────────────────
    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${llm.model-name:llama3}")
    private String llmModelName;

    @Value("${llm.base-url:}")
    private String llmBaseUrl;

    @Value("${llm.api-key:}")
    private String llmApiKey;

    @Value("${llm.temperature:0.7}")
    private double llmTemperature;

    @Value("${llm.timeout-minutes:15}")
    private long llmTimeoutMinutes;

    // ── Chat Model (provider-switchable: ollama / openai / gpt / kimi) ──────
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        String provider = llmProvider == null ? "ollama" : llmProvider.trim().toLowerCase();
        log.info("Configuring chat model — provider='{}', model='{}'", provider, llmModelName);

        return switch (provider) {
            case "ollama" -> buildOllamaChatModel();
            case "openai", "gpt", "kimi" -> buildOpenAiCompatibleChatModel(provider);
            default -> throw new IllegalStateException(
                    "Unknown llm.provider '" + llmProvider + "'. Expected one of: ollama, openai, gpt, kimi. " +
                            "Set it via a profile (--spring.profiles.active=kimi) or the llm.provider property.");
        };
    }

    private ChatLanguageModel buildOllamaChatModel() {
        String baseUrl = llmBaseUrl.isBlank() ? ollamaBaseUrl : llmBaseUrl;
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(llmModelName)
                .timeout(Duration.ofMinutes(llmTimeoutMinutes))
                .temperature(llmTemperature)
                .build();
    }

    private ChatLanguageModel buildOpenAiCompatibleChatModel(String provider) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            throw new IllegalStateException(
                    "llm.api-key is required for provider '" + provider + "'. Set it via an environment " +
                            "variable referenced from the active profile's properties file, e.g. " +
                            "OPENAI_API_KEY or KIMI_API_KEY.");
        }
        var builder = OpenAiChatModel.builder()
                .apiKey(llmApiKey)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .timeout(Duration.ofMinutes(llmTimeoutMinutes));
        if (!llmBaseUrl.isBlank()) {
            builder.baseUrl(llmBaseUrl);
        }
        return builder.build();
    }

    // ── Embedding Model (nomic-embed-text via Ollama — fixed regardless of chat provider) ───
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(1500))
                .build();
    }

    // ── PgVector Embedding Store ─────────────────────────────────────────────
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // Parse jdbc URL → host/port/db for PgVector builder
        // jdbc:postgresql://localhost:5432/ragdb
        String stripped = datasourceUrl.replace("jdbc:postgresql://", "");
        String[] hostAndDb = stripped.split("/");
        String[] hostPort = hostAndDb[0].split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 5432;
        String database = hostAndDb[1];

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(datasourceUsername)
                .password(datasourcePassword)
                .table(tableName)
                .dimension(vectorDimension)
                .createTable(true)
                .build();
    }
}


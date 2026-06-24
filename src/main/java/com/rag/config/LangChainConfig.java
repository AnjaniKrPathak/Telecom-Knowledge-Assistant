package com.rag.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${ollama.chat-model}")
    private String chatModel;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    @Value("${ollama.timeout}")
    private String timeout;

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

    // ── Chat Model (llama3 via Ollama) ──────────────────────────────────────
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(chatModel)
                .timeout(Duration.ofSeconds(120))
                .temperature(0.7)
                .build();
    }

    // ── Embedding Model (nomic-embed-text via Ollama) ────────────────────────
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(60))
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

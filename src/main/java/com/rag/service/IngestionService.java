package com.rag.service;

import com.rag.document.DocumentLoaderService;
import com.rag.document.DocumentType;
import com.rag.model.DocumentRecord;
import com.rag.repository.DocumentRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final DocumentLoaderService loaderService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentRepository documentRepository;

    @Value("${rag.chunk-size}")
    private int chunkSize;

    @Value("${rag.chunk-overlap}")
    private int chunkOverlap;

    // ── Ingest uploaded file ─────────────────────────────────────────────────
    public DocumentRecord ingestFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("Ingesting file: {}", filename);

        Document document = loaderService.load(file);
        return processDocument(document, filename, DocumentType.fromFilename(filename).name());
    }

    // ── Ingest web URL ───────────────────────────────────────────────────────
    public DocumentRecord ingestUrl(String url) throws IOException {
        log.info("Ingesting URL: {}", url);

        if (documentRepository.existsBySource(url)) {
            log.warn("URL already ingested: {}", url);
            throw new IllegalArgumentException("URL already ingested: " + url);
        }

        Document document = loaderService.loadUrl(url);
        return processDocument(document, url, "URL");
    }

    // ── Core processing ──────────────────────────────────────────────────────
    private DocumentRecord processDocument(Document document, String source, String type) {
        // 1. Split into chunks
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);
        log.info("Split '{}' into {} chunks", source, segments.size());

        // 2. Embed chunks
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        log.info("Generated {} embeddings", embeddings.size());

        // 3. Store in pgvector
        embeddingStore.addAll(embeddings, segments);
        log.info("Stored embeddings in pgvector for '{}'", source);

        // 4. Save metadata record
        DocumentRecord record = DocumentRecord.builder()
                .name(source)
                .type(type)
                .source(source)
                .chunkCount(segments.size())
                .build();

        return documentRepository.save(record);
    }
}

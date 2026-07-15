package com.rag.service;

import com.rag.document.DocumentLoaderService;
import com.rag.document.DocumentType;
import com.rag.document.excel.ExcelStructuredChunker;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final DocumentLoaderService loaderService;
    private final EmbeddingModel embeddingModel;
    private final ExcelStructuredChunker excelChunker;
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
    // ── Ingest a file already on disk (used by folder / batch ingestion) ────
    public DocumentRecord ingestFile(File file) throws IOException {
        log.info("Ingesting file from disk: {}", file.getAbsolutePath());

        if (DocumentType.fromFilename(file.getName()) == DocumentType.EXCEL) {
            try (InputStream is = new FileInputStream(file)) {
                return processExcel(is, file.getAbsolutePath());
            }
        }

        Document document = loaderService.load(file);
        return processDocument(document, file.getAbsolutePath(), DocumentType.fromFilename(file.getName()).name());
    }

    // ── Async variant: submits one file to the ingestion thread pool ────────
    // Runs on the "ingestionTaskExecutor" bean defined in AsyncConfig, so that
    // an entire folder can be ingested concurrently instead of one-by-one.
    @Async("ingestionTaskExecutor")
    public CompletableFuture<DocumentRecord> ingestFileAsync(File file) {
        try {
            DocumentRecord record = ingestFile(file);
            return CompletableFuture.completedFuture(record);
        } catch (Exception e) {
            // Wrap so the caller (FolderIngestionService) can unwrap the real
            // cause from the CompletionException and log it against this file.
            CompletableFuture<DocumentRecord> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
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
    // ── Ingest pre-built segments (used by non-file sources, e.g. git commit history) ───────
    // Reuses the same embed + pgvector-store + DocumentRecord bookkeeping as file ingestion,
    // for callers that already have their own domain-specific chunking (see ExcelStructuredChunker
    // for files, or GitIngestionService for commit messages).
    public DocumentRecord ingestSegments(List<TextSegment> segments, String source, String type) {
        if (segments == null || segments.isEmpty()) {
            log.debug("ingestSegments called with no segments for '{}' — nothing to do", source);
            return null;
        }
        embedAndStore(segments, source);
        return saveRecord(source, type, segments.size());
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
    // ── Structured Excel processing ──────────────────────────────────────────
    // Skips the generic recursive splitter entirely: ExcelStructuredChunker already produces
    // one well-formed chunk per row (or small row group), each carrying Workbook/Sheet/Row
    // metadata, so re-splitting by character count would only cut across that structure.
    private DocumentRecord processExcel(InputStream is, String source) throws IOException {
        List<TextSegment> segments = excelChunker.chunk(is, source);
        log.info("Structured-chunked '{}' into {} row-level chunk(s)", source, segments.size());

        if (segments.isEmpty()) {
            log.warn("Excel file '{}' produced no chunks (empty workbook?)", source);
        }

        embedAndStore(segments, source);
        return saveRecord(source, DocumentType.EXCEL.name(), segments.size());
    }

    // ── Shared embed + store step ─────────────────────────────────────────────
    private void embedAndStore(List<TextSegment> segments, String source) {
        int batchSize = 100;
        //  Convert large list of segments into smaller batches to avoid memory issues and improve performance
        for (int i = 0; i < segments.size(); i += batchSize) {

            List<TextSegment> batch = segments.subList(
                    i,
                    Math.min(i + batchSize, segments.size())
            );

            // Embed chunks
            List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
            log.info("Generated {} embeddings", embeddings.size());

            // Store in pgvector
            embeddingStore.addAll(embeddings, batch);
            log.info("Stored embeddings in pgvector for '{}'", source);
            log.info("Stored batch {} - {}",
                    i,
                    Math.min(i + batchSize, segments.size()));
        }
    }

    private DocumentRecord saveRecord(String source, String type, int chunkCount) {
        DocumentRecord record = DocumentRecord.builder()
                .name(source)
                .type(type)
                .source(source)
                .chunkCount(chunkCount)
                .build();

        return documentRepository.save(record);
    }
}

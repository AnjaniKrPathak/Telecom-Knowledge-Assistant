package com.rag.service;

import com.rag.document.DocumentType;
import com.rag.model.BatchStatus;
import com.rag.model.DocumentRecord;
import com.rag.model.IngestionBatch;
import com.rag.model.IngestionFailureRecord;
import com.rag.model.IngestionSuccessRecord;
import com.rag.model.dto.BatchReport;
import com.rag.repository.IngestionBatchRepository;
import com.rag.repository.IngestionFailureRepository;
import com.rag.repository.IngestionSuccessRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles "upload a whole folder" requests.
 *
 * Flow:
 *  1. Scan the folder for supported documents.
 *  2. Create an IngestionBatch record (the job) and return its id immediately.
 *  3. Kick off ingestion of every file concurrently (async, thread-pooled).
 *  4. Once every file has finished (success or failure), persist one row per
 *     outcome into the success queue table or the failure queue table, then
 *     stamp the batch with the final report (counts + status).
 */
@Slf4j
@Service
public class FolderIngestionService {

    private final IngestionService ingestionService;
    private final IngestionBatchRepository batchRepository;
    private final IngestionSuccessRepository successRepository;
    private final IngestionFailureRepository failureRepository;

    // Self-injected proxy: required so that the @Async call to
    // processBatchAsync(...) below actually goes through Spring's AOP proxy
    // instead of being a plain same-object method call (which would run
    // synchronously and block the HTTP request thread). Written as an
    // explicit constructor (rather than via Lombok) so @Lazy is guaranteed
    // to land on the constructor parameter, which is what Spring needs here.
    private final FolderIngestionService self;

    public FolderIngestionService(IngestionService ingestionService,
                                   IngestionBatchRepository batchRepository,
                                   IngestionSuccessRepository successRepository,
                                   IngestionFailureRepository failureRepository,
                                   @Lazy FolderIngestionService self) {
        this.ingestionService = ingestionService;
        this.batchRepository = batchRepository;
        this.successRepository = successRepository;
        this.failureRepository = failureRepository;
        this.self = self;
    }

    /**
     * Starts a new folder ingestion batch. Returns as soon as the folder has
     * been scanned and the batch row created; actual document processing
     * continues in the background thread pool.
     */
    public IngestionBatch startFolderIngestion(String folderPath) {
        Path folder = Path.of(folderPath);

        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Folder does not exist or is not a directory: " + folderPath);
        }

        List<File> files = listSupportedFiles(folder);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No supported documents (.pdf, .docx, .doc, .xlsx, .xls, .txt, .md) found in: " + folderPath);
        }

        IngestionBatch batch = IngestionBatch.builder()
                .folderPath(folder.toAbsolutePath().toString())
                .totalFiles(files.size())
                .successCount(0)
                .failureCount(0)
                .status(BatchStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .build();
        batch = batchRepository.save(batch);
        log.info("Created ingestion batch {} for folder '{}' with {} file(s)", batch.getId(), folderPath, files.size());

        self.processBatchAsync(batch.getId(), files);
        return batch;
    }

    /**
     * Fires off async ingestion for every file, waits (on a background pool
     * thread — not the caller's thread) for all of them to finish, records
     * each outcome in the success/failure queue tables, then finalizes the
     * batch report.
     */
    @Async("ingestionTaskExecutor")
    public void processBatchAsync(String batchId, List<File> files) {
        List<CompletableFuture<Void>> perFileCompletions = files.stream()
                .map(file -> ingestionService.ingestFileAsync(file)
                        .handle((record, ex) -> {
                            if (ex != null) {
                                recordFailure(batchId, file, unwrap(ex));
                            } else {
                                recordSuccess(batchId, file, record);
                            }
                            return  (Void) null;
                        }))
                .collect(Collectors.toList());

        CompletableFuture.allOf(perFileCompletions.toArray(new CompletableFuture[0])).join();

        finalizeBatch(batchId);
    }

    private void recordSuccess(String batchId, File file, DocumentRecord record) {
        IngestionSuccessRecord success = IngestionSuccessRecord.builder()
                .batchId(batchId)
                .fileName(file.getName())
                .filePath(file.getAbsolutePath())
                .documentRecordId(record != null ? record.getId() : null)
                .chunkCount(record != null ? record.getChunkCount() : 0)
                .build();
        successRepository.save(success);
        log.info("[batch {}] SUCCESS: {}", batchId, file.getName());
    }

    private void recordFailure(String batchId, File file, Throwable ex) {
        IngestionFailureRecord failure = IngestionFailureRecord.builder()
                .batchId(batchId)
                .fileName(file.getName())
                .filePath(file.getAbsolutePath())
                .errorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                .build();
        failureRepository.save(failure);
        log.error("[batch {}] FAILURE: {} - {}", batchId, file.getName(), ex.getMessage());
    }

    private void finalizeBatch(String batchId) {
        IngestionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("Batch not found: " + batchId));

        int successCount = successRepository.findByBatchId(batchId).size();
        int failureCount = failureRepository.findByBatchId(batchId).size();

        batch.setSuccessCount(successCount);
        batch.setFailureCount(failureCount);
        batch.setCompletedAt(LocalDateTime.now());
        batch.setStatus(failureCount == 0
                ? BatchStatus.COMPLETED
                : (successCount == 0 ? BatchStatus.FAILED : BatchStatus.COMPLETED_WITH_ERRORS));

        batchRepository.save(batch);
        log.info("Batch {} finished: {} succeeded, {} failed (of {})",
                batchId, successCount, failureCount, batch.getTotalFiles());
    }

    public BatchReport getReport(String batchId) {
        IngestionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        int completed = batch.getSuccessCount() + batch.getFailureCount();
        return BatchReport.builder()
                .batchId(batch.getId())
                .folderPath(batch.getFolderPath())
                .status(batch.getStatus())
                .totalFiles(batch.getTotalFiles())
                .successCount(batch.getSuccessCount())
                .failureCount(batch.getFailureCount())
                .pendingCount(Math.max(0, batch.getTotalFiles() - completed))
                .startedAt(batch.getStartedAt())
                .completedAt(batch.getCompletedAt())
                .build();
    }

    private List<File> listSupportedFiles(Path folder) {
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> DocumentType.isSupportedFile(p.getFileName().toString()))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read folder: " + folder + " - " + e.getMessage(), e);
        }
    }

    private Throwable unwrap(Throwable ex) {
        return (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
    }
}

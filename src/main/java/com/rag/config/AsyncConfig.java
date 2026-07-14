package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables async processing and provides a dedicated, bounded thread pool
 * used for concurrent document ingestion (folder / batch uploads).
 * Also enables @Scheduled, used by Bm25CorpusStats to periodically refresh
 * corpus-wide BM25 statistics.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Value("${rag.ingestion.core-pool-size:1}")
    private int corePoolSize;

    @Value("${rag.ingestion.max-pool-size:1}")
    private int maxPoolSize;

    @Value("${rag.ingestion.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = "ingestionTaskExecutor")
    public Executor ingestionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(1500);
        executor.initialize();
        return executor;
    }

<<<<<<< HEAD
    // Separate, smaller pool for git clone/pull/walk jobs — kept independent from document
    // ingestion so a slow repo clone can't starve file uploads (and vice versa).
    @Value("${rag.git.core-pool-size:2}")
    private int gitCorePoolSize;

    @Value("${rag.git.max-pool-size:4}")
    private int gitMaxPoolSize;

    @Value("${rag.git.queue-capacity:50}")
    private int gitQueueCapacity;

    @Bean(name = "gitTaskExecutor")
    public Executor gitTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(gitCorePoolSize);
        executor.setMaxPoolSize(gitMaxPoolSize);
        executor.setQueueCapacity(gitQueueCapacity);
        executor.setThreadNamePrefix("git-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(1800);
        executor.initialize();
        return executor;
    }

=======
>>>>>>> origin/feather/cache-integration
    @Override
    public Executor getAsyncExecutor() {
        return ingestionTaskExecutor();
    }
}

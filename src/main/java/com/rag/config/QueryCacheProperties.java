package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.cache.*" properties from application.yml.
 *
 * rag:
 *   cache:
 *     enabled: true
 *     ttl-minutes: 60        # how long a cached answer stays valid before it's re-generated
 *     max-size: 500           # max distinct questions held in memory at once (LRU-evicted beyond this)
 *     normalize-whitespace: true  # collapse repeated whitespace + trim before hashing the question
 *     case-sensitive: false       # if false, "What is X?" and "what is x?" share the same cache entry
 */
@Configuration
@ConfigurationProperties(prefix = "rag.cache")
@Data
public class QueryCacheProperties {

    /** Master switch. When false, RagQueryService always hits the LLM/retrieval pipeline. */
    private boolean enabled = true;

    /** Time-to-live for a cached answer, in minutes. Entry expires this long after it was written. */
    private long ttlMinutes = 60;

    /** Max number of distinct cached questions held in memory (Caffeine evicts least-recently-used beyond this). */
    private long maxSize = 500;

    /** Collapse repeated whitespace and trim the question before using it as a cache key. */
    private boolean normalizeWhitespace = true;

    /** If false, cache key matching ignores case ("What is X?" and "what is x?" hit the same entry). */
    private boolean caseSensitive = false;
}

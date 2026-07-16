package com.rag.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.rag.config.QueryCacheProperties;
import com.rag.service.RagQueryService.RagResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory, TTL-bound cache for RAG query answers.
 *
 * If the same question (after normalization) is asked again within the configured TTL,
 * RagQueryService returns the cached answer instead of re-running retrieval + LLM generation.
 * Backed by Caffeine so expiry, size-eviction, and hit/miss stats all come for free.
 *
 * Cache key = normalized question text. Normalization (trim / collapse whitespace / lowercase)
 * is controlled by rag.cache.normalize-whitespace and rag.cache.case-sensitive so that trivially
 * different phrasing of the same question ("What is X?" vs "what is x?") still hits the cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCacheService {

    private final QueryCacheProperties properties;

    private Cache<String, RagResponse> cache;

    @PostConstruct
    void init() {
        cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(properties.getTtlMinutes()))
                .maximumSize(properties.getMaxSize())
                .recordStats()
                .build();

        log.info("🗄️  Query cache initialized (enabled={}, ttl={}min, maxSize={})",
                properties.isEnabled(), properties.getTtlMinutes(), properties.getMaxSize());
    }

    /** Returns the cached answer for this question, if present and not expired. */
    public Optional<RagResponse> get(String question) {
        log.info("Cache check for question: \"{}\"", question+"properties.isEnabled()="+properties.isEnabled());
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        String key = normalize(question);
        RagResponse cached = cache.getIfPresent(key);
        log.info("Cache check for question: \"{}\" — present={}", question, cached );
        if (cached != null) {
            log.info("Cache HIT for question: \"{}\"", question);
        } else {
            log.info("Cache MISS for question: \"{}\"", question);
        }
        return Optional.ofNullable(cached);
    }

    /** Stores the answer for this question, keyed by its normalized form. */
    public void put(String question, RagResponse response) {
        if (!properties.isEnabled()) {
            return;
        }
        cache.put(normalize(question), response);
        log.debug("💾 Cached answer for question: \"{}\" (TTL={}min)", question, properties.getTtlMinutes());
    }

    /** Evicts a single question's cached answer, if present. */
    public boolean evict(String question) {
        String key = normalize(question);
        boolean existed = cache.getIfPresent(key) != null;
        cache.invalidate(key);
        return existed;
    }

    /** Clears every cached answer. */
    public void clear() {
        long sizeBefore = cache.estimatedSize();
        cache.invalidateAll();
        log.info("🧹 Query cache cleared ({} entries removed)", sizeBefore);
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    private String normalize(String question) {
        String normalized = question;
        if (properties.isNormalizeWhitespace()) {
            normalized = normalized.trim().replaceAll("\\s+", " ");
        }
        if (!properties.isCaseSensitive()) {
            normalized = normalized.toLowerCase();
        }
        return normalized;
    }
}

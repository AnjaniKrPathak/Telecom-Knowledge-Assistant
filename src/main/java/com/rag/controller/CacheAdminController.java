package com.rag.controller;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.rag.config.QueryCacheProperties;
import com.rag.service.QueryCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Lets you inspect and manage the RAG query-answer cache without restarting the app —
 * e.g. to force-clear stale answers right after re-ingesting documents.
 */
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
@Tag(name = "Cache Admin", description = "Inspect and manage the RAG query answer cache")
public class CacheAdminController {

    private final QueryCacheService queryCacheService;
    private final QueryCacheProperties properties;

    @GetMapping("/stats")
    @Operation(summary = "Cache config + hit/miss stats + current entry count")
    public Map<String, Object> stats() {
        CacheStats stats = queryCacheService.stats();
        return Map.of(
                "enabled", properties.isEnabled(),
                "ttlMinutes", properties.getTtlMinutes(),
                "maxSize", properties.getMaxSize(),
                "currentSize", queryCacheService.size(),
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount()
        );
    }

    @PostMapping("/clear")
    @Operation(summary = "Clear every cached answer")
    public Map<String, String> clear() {
        queryCacheService.clear();
        return Map.of("status", "cache cleared");
    }

    @DeleteMapping
    @Operation(summary = "Evict the cached answer for one specific question, if present")
    public ResponseEntity<Map<String, String>> evict(@RequestParam String question) {
        boolean existed = queryCacheService.evict(question);
        return ResponseEntity.ok(Map.of(
                "question", question,
                "status", existed ? "evicted" : "not found in cache"
        ));
    }
}

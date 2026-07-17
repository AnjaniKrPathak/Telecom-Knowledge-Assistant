package com.rag.controller;

import com.rag.config.RerankerProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** Lets you confirm the active reranker backend and check whether the Hugging Face TEI server is reachable. */
@RestController
@RequestMapping("/api/admin/reranker")
@RequiredArgsConstructor
@Tag(name = "Reranker Admin", description = "Inspect the active reranking backend and its health")
public class RerankerAdminController {

    private final RerankerProperties properties;

    @Qualifier("rerankerRestTemplate")
    private final RestTemplate rerankerRestTemplate;

    @GetMapping("/status")
    @Operation(summary = "Current reranker config, plus a live reachability check of the configured Hugging Face TEI server")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("provider", properties.getProvider());
        result.put("candidatePoolSize", properties.getCandidatePoolSize());
        result.put("failOpen", properties.isFailOpen());
        result.put("huggingFaceBaseUrl", properties.getHuggingface().getBaseUrl());

        if ("huggingface".equals(properties.getProvider())) {
            result.put("huggingFaceReachable", pingHuggingFace());
        }
        return ResponseEntity.ok(result);
    }

    private boolean pingHuggingFace() {
        try {
            rerankerRestTemplate.getForEntity(properties.getHuggingface().getBaseUrl() + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

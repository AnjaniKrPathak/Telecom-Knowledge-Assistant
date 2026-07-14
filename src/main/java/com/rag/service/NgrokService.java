package com.rag.service;

import com.rag.config.NgrokProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects the current public HTTPS URL of the local ngrok tunnel by polling
 * ngrok's local Agent API (http://localhost:4040/api/tunnels).
 *
 * ngrok assigns a brand-new random URL every time the tunnel/agent restarts
 * (e.g. every day on the free plan), so this must be re-checked rather than
 * read once from config.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NgrokService {

    private final RestTemplate restTemplate;
    private final NgrokProperties ngrokProperties;

    /** Last URL we successfully detected, so callers can cheaply check "did it change?". */
    private final AtomicReference<String> lastKnownUrl = new AtomicReference<>();

    /**
     * Fetches the current ngrok public HTTPS URL, retrying a single time only
     * (used for frequent/lightweight polling once the app is already running).
     *
     * @throws IllegalStateException if ngrok isn't reachable or has no HTTPS tunnel
     */
    public String getPublicUrl() {
        String url = fetchOnce();
        lastKnownUrl.set(url);
        return url;
    }

    /**
     * Fetches the current ngrok public HTTPS URL, retrying with backoff.
     * Intended for application startup, since ngrok's own process may still be
     * warming up when Spring Boot's ApplicationRunner fires.
     *
     * @throws IllegalStateException if ngrok never became reachable within the retry budget
     */
    public String getPublicUrlWithRetry() {
        int maxAttempts = Math.max(1, ngrokProperties.getStartupMaxRetries());
        long delayMs = ngrokProperties.getStartupRetryDelayMs();

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String url = fetchOnce();
                lastKnownUrl.set(url);
                return url;
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("ngrok not ready yet (attempt {}/{}): {}. Retrying in {} ms...",
                            attempt, maxAttempts, e.getMessage(), delayMs);
                    sleep(delayMs);
                }
            }
        }
        throw lastError;
    }

    /** The most recent URL this service has successfully detected, if any. */
    public Optional<String> getLastKnownUrl() {
        return Optional.ofNullable(lastKnownUrl.get());
    }

    @SuppressWarnings("unchecked")
    private String fetchOnce() {
        Map<String, Object> response;
        try {
            response = restTemplate.getForObject(ngrokProperties.getApiUrl(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not reach ngrok's local API at " + ngrokProperties.getApiUrl()
                            + " — is 'ngrok http <port>' running?", e);
        }

        if (response == null) {
            throw new IllegalStateException("Empty response from ngrok API at " + ngrokProperties.getApiUrl());
        }

        List<Map<String, Object>> tunnels = (List<Map<String, Object>>) response.get("tunnels");
        if (tunnels == null || tunnels.isEmpty()) {
            throw new IllegalStateException("ngrok is running but has no active tunnels");
        }

        return tunnels.stream()
                .map(t -> (String) t.get("public_url"))
                .filter(url -> url != null && url.startsWith("https"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No HTTPS tunnel found among active ngrok tunnels"));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

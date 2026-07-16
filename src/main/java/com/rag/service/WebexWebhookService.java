package com.rag.service;

import com.rag.model.dto.CreateWebhookRequest;
import com.rag.model.dto.UpdateWebhookRequest;
import com.rag.model.dto.WebhookItem;
import com.rag.model.dto.WebhookListResponse;
import com.rag.webex.WebexProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Keeps exactly one Webex webhook (identified by name) pointed at whatever
 * ngrok URL is currently active, with zero manual steps in the Webex Developer Portal.
 *
 * Flow on every sync:
 *   1. Detect current ngrok public URL      (NgrokService)
 *   2. List Webex webhooks, find ours by name
 *   3. None exist        -> create it
 *   4. One exists        -> update it if the URL changed, otherwise leave it alone
 *   5. Duplicates exist  -> keep the oldest, delete the rest (if enabled)
 *   6. Re-fetch the webhook and verify targetUrl/status match what we expect
 *
 * Triggered once at startup (WebhookInitializer) and then periodically
 * (NgrokWebhookScheduler) so a mid-session ngrok restart is picked up automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebexWebhookService {

    private final RestTemplate restTemplate;
    private final NgrokService ngrokService;
    private final WebexProperties properties;

    /** ngrok URL Webex was last successfully synced to, so the scheduler can skip API calls when nothing changed. */
    private final AtomicReference<String> lastSyncedUrl = new AtomicReference<>();

    // ---------- Public entry points ----------

    /** Called once at application startup. Retries while ngrok warms up, and never throws (logs instead). */
    public void syncOnStartup() {
        String publicUrl;
        try {
            publicUrl = ngrokService.getPublicUrlWithRetry();
        } catch (Exception e) {
            log.error("❌ Could not detect an ngrok tunnel — is 'ngrok http <port>' running? " +
                    "Webex webhook was NOT configured. Cause: {}", e.getMessage());
            return;
        }
        sync(publicUrl);
    }

    /** Cheap periodic check: only touches the Webex API if the ngrok URL actually changed since last sync. */
    public void checkAndSyncIfChanged() {
        String publicUrl;
        try {
            publicUrl = ngrokService.getPublicUrl();
        } catch (Exception e) {
            log.warn("⚠️ ngrok tunnel not reachable during scheduled check: {}", e.getMessage());
            return;
        }

        if (publicUrl.equals(lastSyncedUrl.get())) {
            log.debug("ngrok URL unchanged ({}). Skipping Webex sync.", publicUrl);
            return;
        }

        log.info("🔄 Detected a new ngrok URL ({}). Re-syncing Webex webhook...", publicUrl);
        sync(publicUrl);
    }

    /** Kept for backward compatibility with existing callers (e.g. WebhookInitializer). */
    public void updateWebhook() {
        syncOnStartup();
    }

    // ---------- Core sync logic ----------

    private void sync(String ngrokPublicUrl) {
        String targetUrl = ngrokPublicUrl + properties.getWebhookTargetPath();

        log.info("==================== Webex Webhook Sync ====================");
        log.info("Detected ngrok URL   : {}", ngrokPublicUrl);
        log.info("Target webhook URL   : {}", targetUrl);

        List<WebhookDefinition> definitions = new java.util.ArrayList<>();
        definitions.add(new WebhookDefinition(
                properties.getWebhookName(), properties.getWebhookResource(), properties.getWebhookEvent()));
        if (properties.isAttachmentActionsWebhookEnabled()) {
            // Powers the inline 👍 Like / 👎 Dislike buttons — Webex fires this event whenever
            // someone taps an Action.Submit button on a card we sent (see WebexBotService).
            definitions.add(new WebhookDefinition(properties.getActionsWebhookName(), "attachmentActions", "created"));
        }

        boolean anyFailure = false;
        for (WebhookDefinition def : definitions) {
            try {
                syncOneWebhook(def, targetUrl);
            } catch (Exception e) {
                anyFailure = true;
                log.error("❌ Webex webhook sync failed for '{}' ({}/{}): {}",
                        def.name(), def.resource(), def.event(), e.getMessage(), e);
            }
        }
        if (!anyFailure) {
            lastSyncedUrl.set(ngrokPublicUrl);
        }

        log.info("=============================================================");
    }

    private void syncOneWebhook(WebhookDefinition def, String targetUrl) {
        List<WebhookItem> matches = findWebhooksByName(def.name());
        log.info("Existing webhooks named '{}': {}", def.name(), matches.size());

        if (matches.isEmpty()) {
            WebhookItem created = createWebhook(def.name(), def.resource(), def.event(), targetUrl);
            log.info("✅ Created webhook '{}' (id={}) -> {}", created.getName(), created.getId(), created.getTargetUrl());
            verifyWebhook(created.getId(), targetUrl);
            return;
        }

        matches.sort(Comparator.comparing(
                WebhookItem::getCreated, Comparator.nullsLast(Comparator.naturalOrder())));
        WebhookItem primary = matches.get(0);

        if (matches.size() > 1) {
            log.warn("⚠️ Found {} duplicate webhooks named '{}'.", matches.size(), def.name());
            if (properties.isRemoveDuplicateWebhooks()) {
                for (WebhookItem dup : matches.subList(1, matches.size())) {
                    deleteWebhook(dup.getId());
                    log.info("🗑️ Removed duplicate webhook (id={})", dup.getId());
                }
            }
        }

        if (targetUrl.equals(primary.getTargetUrl())) {
            log.info("✅ Webhook '{}' (id={}) already points to the current URL — no update needed.",
                    primary.getName(), primary.getId());
        } else {
            log.info("Updating webhook (id={}): '{}' -> '{}'", primary.getId(), primary.getTargetUrl(), targetUrl);
            updateWebhookUrl(primary.getId(), def.name(), targetUrl);
            log.info("✅ Webhook updated successfully.");
        }
        verifyWebhook(primary.getId(), targetUrl);
    }

    /** One "our webhook should exist with this name/resource/event" rule the sync loop enforces. */
    private record WebhookDefinition(String name, String resource, String event) {}

    // ---------- Webex API calls ----------

    public List<WebhookItem> listWebhooks() {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
        ResponseEntity<WebhookListResponse> response = restTemplate.exchange(
                properties.getApiBaseUrl() + "/webhooks",
                HttpMethod.GET,
                entity,
                WebhookListResponse.class);
        WebhookListResponse body = response.getBody();
        return (body != null && body.getItems() != null) ? body.getItems() : List.of();
    }

    public List<WebhookItem> findWebhooksByName(String name) {
        return listWebhooks().stream()
                .filter(w -> name.equals(w.getName()))
                .collect(Collectors.toList());
    }

    public WebhookItem createWebhook(String name, String resource, String event, String targetUrl) {
        CreateWebhookRequest request = CreateWebhookRequest.builder()
                .name(name)
                .targetUrl(targetUrl)
                .resource(resource)
                .event(event)
                .secret(blankToNull(properties.getWebhookSecret()))
                .build();

        HttpEntity<CreateWebhookRequest> entity = new HttpEntity<>(request, jsonHeaders());
        ResponseEntity<WebhookItem> response = restTemplate.exchange(
                properties.getApiBaseUrl() + "/webhooks",
                HttpMethod.POST,
                entity,
                WebhookItem.class);
        return response.getBody();
    }

    public void updateWebhookUrl(String webhookId, String name, String targetUrl) {
        UpdateWebhookRequest request = UpdateWebhookRequest.builder()
                .name(name)
                .targetUrl(targetUrl)
                .secret(blankToNull(properties.getWebhookSecret()))
                .build();

        HttpEntity<UpdateWebhookRequest> entity = new HttpEntity<>(request, jsonHeaders());
        restTemplate.exchange(
                properties.getApiBaseUrl() + "/webhooks/" + webhookId,
                HttpMethod.PUT,
                entity,
                String.class);
    }

    public void deleteWebhook(String webhookId) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
        restTemplate.exchange(
                properties.getApiBaseUrl() + "/webhooks/" + webhookId,
                HttpMethod.DELETE,
                entity,
                Void.class);
    }

    /** Re-fetches the webhook by id and confirms its targetUrl/status match what we just set. */
    public void verifyWebhook(String webhookId, String expectedTargetUrl) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
            ResponseEntity<WebhookItem> response = restTemplate.exchange(
                    properties.getApiBaseUrl() + "/webhooks/" + webhookId,
                    HttpMethod.GET,
                    entity,
                    WebhookItem.class);
            WebhookItem item = response.getBody();

            if (item == null) {
                log.warn("⚠️ Verification failed: webhook {} returned no body", webhookId);
                return;
            }

            boolean urlMatches = expectedTargetUrl.equals(item.getTargetUrl());
            boolean isActive = item.getStatus() == null || "active".equalsIgnoreCase(item.getStatus());

            if (urlMatches && isActive) {
                log.info("🔎 Verified: webhook '{}' (id={}) is ACTIVE at {}", item.getName(), item.getId(), item.getTargetUrl());
            } else {
                log.warn("⚠️ Verification mismatch for webhook {}: status={}, targetUrl={} (expected {})",
                        webhookId, item.getStatus(), item.getTargetUrl(), expectedTargetUrl);
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not verify webhook {} after sync: {}", webhookId, e.getMessage());
        }
    }

    // ---------- helpers ----------

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getBotToken());
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

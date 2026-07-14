package com.rag.startup;

import com.rag.service.WebexWebhookService;
import com.rag.webex.WebexProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background safety net for ngrok's rotating URL. WebhookInitializer handles the
 * URL at startup; this catches the case where ngrok is restarted (new URL assigned)
 * while the Spring Boot app keeps running, without requiring an app restart or any
 * manual change in the Webex Developer Portal.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NgrokWebhookScheduler {

    private final WebexWebhookService webhookService;
    private final WebexProperties properties;

    @Scheduled(
            initialDelayString = "${webex.auto-sync-interval-ms:60000}",
            fixedDelayString = "${webex.auto-sync-interval-ms:60000}")
    public void checkNgrokUrl() {
        if (!properties.isAutoSyncEnabled()) {
            return;
        }
        webhookService.checkAndSyncIfChanged();
    }
}

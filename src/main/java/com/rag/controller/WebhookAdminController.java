package com.rag.controller;

import com.rag.model.dto.WebhookItem;
import com.rag.service.WebexWebhookService;
import com.rag.webex.WebexProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lets you verify/force the ngrok -> Webex webhook sync without touching the
 * Webex Developer Portal. Useful right after startup or if you suspect ngrok
 * rotated its URL faster than the background scheduler noticed.
 */
@RestController
@RequestMapping("/api/admin/webhook")
@RequiredArgsConstructor
@Tag(name = "Webhook Admin", description = "Inspect and manually re-sync the Webex webhook against the current ngrok URL")
public class WebhookAdminController {

    private final WebexWebhookService webhookService;
    private final WebexProperties properties;

    @GetMapping("/status")
    @Operation(summary = "List Webex webhooks matching the configured names — confirms what's currently registered " +
            "(the \"messages\" webhook for questions, and the \"attachmentActions\" webhook that powers inline Like/Dislike buttons)")
    public Map<String, List<WebhookItem>> status() {
        Map<String, List<WebhookItem>> result = new java.util.LinkedHashMap<>();
        result.put("messages", webhookService.findWebhooksByName(properties.getWebhookName()));
        if (properties.isAttachmentActionsWebhookEnabled()) {
            result.put("attachmentActions", webhookService.findWebhooksByName(properties.getActionsWebhookName()));
        }
        return result;
    }

    @PostMapping("/resync")
    @Operation(summary = "Force an immediate re-check of the ngrok URL and re-sync the Webex webhook")
    public Map<String, String> resync() {
        webhookService.syncOnStartup();
        return Map.of("status", "resync triggered — check application logs for the result");
    }
}

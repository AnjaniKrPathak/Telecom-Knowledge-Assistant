package com.rag.webex;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "webex.*" properties from application.yml.
 *
 * webex:
 *   bot-token: <your bot access token>
 *   api-base-url: https://webexapis.com/v1
 *   webhook-secret: <optional secret used to validate X-Spark-Signature>
 *   bot-email: yourbot@webex.bot
 */
@Configuration
@ConfigurationProperties(prefix = "webex")
@Data
public class WebexProperties {

    /** Bot access token, created at https://developer.webex.com/my-apps */
    private String botToken;

    /** Base URL for the Webex REST API. */
    private String apiBaseUrl = "https://webexapis.com/v1";

    /** Optional secret configured on the webhook, used to verify X-Spark-Signature. */
    private String webhookSecret;

    /** The bot's own email address, used to ignore its own messages and detect @mentions. */
    private String botEmail;

    // ---- Auto webhook sync (ngrok URL changes -> Webex webhook kept in sync) ----

    /** Name used to find/create/update "our" webhook among all webhooks on the account. */
    private String webhookName = "SpringBootWebhook";

    /** Path appended to the current ngrok public URL to build the webhook's targetUrl. */
    private String webhookTargetPath = "/api/webex/webhook";

    /** Webex resource type to subscribe to (only used when creating a new webhook). */
    private String webhookResource = "messages";

    /** Webex event type to subscribe to (only used when creating a new webhook). */
    private String webhookEvent = "created";

    /** If more than one webhook exists with webhookName, delete all but the oldest one. */
    private boolean removeDuplicateWebhooks = true;

    /**
     * Name used to find/create/update the SECOND webhook — subscribed to attachmentActions/created,
     * i.e. Adaptive Card button taps — which powers the inline 👍 Like / 👎 Dislike buttons shown
     * under every answer. Kept in sync alongside the messages webhook with zero manual portal steps.
     */
    private String actionsWebhookName = "SpringBootWebhookActions";

    /** Master switch for inline Like/Dislike buttons. When false, only the "messages" webhook is managed and feedback stays reply-text-only ("👍"/"👎"). */
    private boolean attachmentActionsWebhookEnabled = true;

    /** Whether to periodically re-check the ngrok URL after startup and re-sync automatically. */
    private boolean autoSyncEnabled = true;

    /** How often (ms) the background scheduler checks whether the ngrok URL changed. */
    private long autoSyncIntervalMs = 60_000;
}

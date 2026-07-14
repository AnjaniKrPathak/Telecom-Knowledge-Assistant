package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "ngrok.*" properties from application.yml.
 *
 * ngrok exposes a local admin API (http://localhost:4040/api/tunnels by default)
 * that reports the currently active public URL(s). We poll it instead of hardcoding
 * a URL, since ngrok assigns a new random URL every time the agent restarts
 * (unless you're on a paid plan with a reserved domain).
 */
@Configuration
@ConfigurationProperties(prefix = "ngrok")
@Data
public class NgrokProperties {

    /** Local ngrok Agent API endpoint that lists active tunnels. */
    private String apiUrl = "http://localhost:4040/api/tunnels";

    /** Max attempts to find a tunnel at application startup (ngrok may still be starting up). */
    private int startupMaxRetries = 10;

    /** Delay in ms between startup retries. */
    private long startupRetryDelayMs = 3000;
}

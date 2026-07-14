package com.rag.startup;

import com.rag.service.WebexWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookInitializer implements ApplicationRunner{
    private final WebexWebhookService webhookService;

        @Override
        public void run(ApplicationArguments args) {
            log.info("Initializing Webex webhook against the current ngrok tunnel...");
            try {
                webhookService.syncOnStartup();
            } catch (Exception e) {
                // Never fail application startup because ngrok/Webex isn't reachable yet;
                // the scheduled check (NgrokWebhookScheduler) will keep retrying.
                log.error("Webex webhook initialization failed. The app will keep running, " +
                        "but Webex won't be able to reach it until this is resolved: {}", e.getMessage(), e);
            }
        }
}

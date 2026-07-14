package com.rag.webex;


import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableAsync // required so WebexBotService.handleWebhookEvent(@Async) runs off the webhook request thread
public class WebexRestConfig {

    @Bean
    public RestTemplate webexRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMinutes(5))
                .setReadTimeout(Duration.ofMinutes(5))
                .build();
    }
}

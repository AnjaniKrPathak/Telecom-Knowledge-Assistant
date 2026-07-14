package com.rag.webex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the X-Spark-Signature header Webex sends with every webhook call,
 * proving the request actually came from Webex and not a random client.
 * https://developer.webex.com/docs/api/guides/webhooks#handling-webhook-payloads-securely
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebexSignatureValidator {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private final WebexProperties webexProperties;

    /** Returns true if no secret is configured (skips validation) or the signature matches. */
    public boolean isValid(String rawBody, String signatureHeader) {
        String secret = webexProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return true; // no secret configured — validation disabled
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computedHex = bytesToHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Failed to validate Webex webhook signature", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

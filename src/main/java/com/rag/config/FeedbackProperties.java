package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.feedback.*" properties from application.yml.
 *
 * rag:
 *   feedback:
 *     enabled: true
 *     boost-weight: 0.03        # score adjustment applied per net vote (up - down) for a source
 *     max-boost: 0.2            # cap on the absolute adjustment a single source can receive
 *     min-votes-for-adjustment: 1  # a source needs at least this many net votes before it's nudged
 *     webex-text-shortcuts-enabled: true  # let Webex users reply "👍"/"👎" to rate the last answer
 */
@Configuration
@ConfigurationProperties(prefix = "rag.feedback")
@Data
public class FeedbackProperties {

    /** Master switch. When false, feedback is still accepted/stored but never influences retrieval ranking. */
    private boolean enabled = true;

    /** How much a single net vote (up - down) shifts a source's retrieval score, up or down. */
    private double boostWeight = 0.03;

    /** Absolute cap on the total adjustment any one source can accumulate, so a few votes can't dominate. */
    private double maxBoost = 0.2;

    /** Minimum |net votes| a source must have before any adjustment is applied (avoids noise from 1 stray vote). */
    private int minVotesForAdjustment = 1;

    /** If true, a plain "👍"/"👎" (or a few text equivalents) reply in Webex rates the bot's last answer. */
    private boolean webexTextShortcutsEnabled = true;

    /** How long (minutes) the bot waits for an optional free-text comment after a Webex thumbs rating before giving up. */
    private int webexCommentPromptTtlMinutes = 5;
}

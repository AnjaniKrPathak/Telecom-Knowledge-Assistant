package com.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the "rag.memory.*" properties from application.yml.
 *
 * rag:
 *   memory:
 *     enabled: true
 *     max-turns: 5                # prior user/assistant turn PAIRS folded into the prompt
 *     max-history-chars: 4000     # hard cap on the history text injected into the prompt
 *     persist: true                # store every turn in Postgres (conversation_messages)
 *     retention-days: 30           # scheduled cleanup deletes turns older than this
 */
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
@Data
public class ConversationMemoryProperties {

    /** Master switch. When false, no history is stored or injected — behaves like the old stateless RAG. */
    private boolean enabled = true;

    /** How many previous user/assistant turn pairs to fold into the prompt as conversation history. */
    private int maxTurns = 5;

    /** Upper bound on the total size (characters) of the history block injected into the prompt. */
    private int maxHistoryChars = 4000;

    /** Whether turns are persisted to Postgres (conversation_messages) for durability across restarts. */
    private boolean persist = true;

    /** Conversation turns older than this many days are purged by the nightly cleanup job. */
    private int retentionDays = 30;
}

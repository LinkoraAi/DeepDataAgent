package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;

/**
 * Session-scoped identity and timing information for a long-term memory instance.
 */
public record MemorySessionContext(
        String sessionId,
        Instant createdAt
) {

    /**
     * Convenience constructor that auto-fills createdAt.
     */
    public MemorySessionContext(String sessionId) {
        this(sessionId, null);
    }

    public MemorySessionContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

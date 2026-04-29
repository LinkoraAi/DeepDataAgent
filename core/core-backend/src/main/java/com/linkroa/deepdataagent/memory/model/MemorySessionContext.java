package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;

/**
 * Session-scoped identity and timing information for a long-term memory instance.
 */
public record MemorySessionContext(
        String sessionId,
        String userName,
        Instant createdAt
) {

    public MemorySessionContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        userName = (userName == null || userName.isBlank()) ? "user" : userName;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

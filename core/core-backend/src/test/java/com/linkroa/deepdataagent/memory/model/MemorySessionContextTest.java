package com.linkroa.deepdataagent.memory.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MemorySessionContextTest {

    @Test
    void should_rejectBlankSessionId_when_constructed() {
        assertThrows(IllegalArgumentException.class, () -> new MemorySessionContext(" ", "user", Instant.now()));
    }

    @Test
    void should_defaultOptionalFields_when_constructedWithNulls() {
        Instant before = Instant.now();
        MemorySessionContext context = new MemorySessionContext("session-main", null, null);
        Instant after = Instant.now();

        assertEquals("session-main", context.sessionId());
        assertEquals("user", context.userName());
        assertNotNull(context.createdAt());
        assertTrue(!context.createdAt().isBefore(before) && !context.createdAt().isAfter(after));
    }
}

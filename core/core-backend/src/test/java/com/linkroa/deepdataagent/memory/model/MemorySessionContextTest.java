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
        assertThrows(IllegalArgumentException.class, () -> new MemorySessionContext(" "));
    }

    @Test
    void should_rejectNullSessionId_when_constructed() {
        assertThrows(IllegalArgumentException.class, () -> new MemorySessionContext(null));
    }

    @Test
    void should_defaultCreatedAt_when_constructedWithNullCreatedAt() {
        Instant before = Instant.now();
        MemorySessionContext context = new MemorySessionContext("session-main", null);
        Instant after = Instant.now();

        assertEquals("session-main", context.sessionId());
        assertNotNull(context.createdAt());
        assertTrue(!context.createdAt().isBefore(before) && !context.createdAt().isAfter(after));
    }

    @Test
    void should_useProvidedCreatedAt_when_constructedWithExplicitTimestamp() {
        Instant customTime = Instant.now().minusSeconds(3600);
        MemorySessionContext context = new MemorySessionContext("session-explicit", customTime);

        assertEquals("session-explicit", context.sessionId());
        assertEquals(customTime, context.createdAt());
    }

    @Test
    void should_supportConvenienceConstructor_when_onlySessionIdProvided() {
        Instant before = Instant.now();
        MemorySessionContext context = new MemorySessionContext("session-convenience");
        Instant after = Instant.now();

        assertEquals("session-convenience", context.sessionId());
        assertNotNull(context.createdAt());
        assertTrue(!context.createdAt().isBefore(before) && !context.createdAt().isAfter(after));
    }
}

package com.linkroa.deepdataagent.shared.idempotency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link IdempotencyRecord} 边界不变量单测。
 */
class IdempotencyRecordTest {

    @Test
    void should_holdAllFields_when_construct_given_validRecord() {
        // given // when
        IdempotencyRecord record = new IdempotencyRecord(
                "key-1", 1L, "post_agents", "abc123", 200, "{\"data\":{}}");

        // then
        assertEquals("key-1", record.idempotencyKey());
        assertEquals(1L, record.ownerId());
        assertEquals("post_agents", record.scope());
        assertEquals("abc123", record.requestHash());
        assertEquals(200, record.responseStatus());
        assertEquals("{\"data\":{}}", record.responseBody());
    }

    @Test
    void should_normalizeEmptyBody_when_construct_given_nullResponseBody() {
        // given // when
        IdempotencyRecord record = new IdempotencyRecord(
                "key-1", 1L, "post_agents", "abc123", 204, null);

        // then
        assertEquals("", record.responseBody());
    }

    @Test
    void should_throwException_when_construct_given_blankKey() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord(" ", 1L, "post_agents", "abc123", 200, "{}"));
    }

    @Test
    void should_throwException_when_construct_given_nullOwner() {
        // given // when // then
        assertThrows(NullPointerException.class,
                () -> new IdempotencyRecord("key-1", null, "post_agents", "abc123", 200, "{}"));
    }

    @Test
    void should_throwException_when_construct_given_blankScope() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord("key-1", 1L, " ", "abc123", 200, "{}"));
    }

    @Test
    void should_throwException_when_construct_given_blankRequestHash() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord("key-1", 1L, "post_agents", " ", 200, "{}"));
    }
}
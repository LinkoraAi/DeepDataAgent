package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SessionCursor} 值对象单测：编码 / 解析 / 不变量。
 */
class SessionCursorTest {

    @Test
    void should_roundTrip_when_encodeThenParse_given_validCursor() {
        // given
        SessionCursor cursor = SessionCursor.of(OffsetDateTime.parse("2026-08-22T10:00:00+08:00"), 7L);

        // when
        SessionCursor parsed = SessionCursor.parse(cursor.encode());

        // then
        assertEquals(cursor.createdAt(), parsed.createdAt());
        assertEquals(7L, parsed.id());
    }

    @Test
    void should_returnNull_when_parse_given_blankOrNull() {
        // when & then
        assertNull(SessionCursor.parse(null));
        assertNull(SessionCursor.parse(""));
        assertNull(SessionCursor.parse("   "));
    }

    @Test
    void should_returnNull_when_parse_given_invalidValue() {
        // when & then
        assertNull(SessionCursor.parse("not-a-base64!!"));
        assertNull(SessionCursor.parse("bm8tY29sb24="));
    }

    @Test
    void should_throw_when_construct_given_nullFields() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> SessionCursor.of(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> SessionCursor.of(OffsetDateTime.now(), null));
    }

    @Test
    void should_encodeWithoutPadding_when_encode_given_cursor() {
        // given
        SessionCursor cursor = SessionCursor.of(OffsetDateTime.parse("2026-08-22T10:00:00+08:00"), 7L);

        // when
        String encoded = cursor.encode();

        // then
        assertNotNull(encoded);
        assertEquals(false, encoded.endsWith("="));
    }
}
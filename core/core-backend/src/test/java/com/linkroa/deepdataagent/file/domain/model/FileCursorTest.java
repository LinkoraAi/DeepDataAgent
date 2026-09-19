package com.linkroa.deepdataagent.file.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileCursor} 游标值对象单测（编码回环与非法输入容错）。
 */
class FileCursorTest {

    @Test
    void should_roundTrip_when_encodeAndParse_given_validCursor() {
        // given
        Instant instant = Instant.parse("2026-09-03T10:15:30Z");
        FileCursor cursor = new FileCursor(instant, 42L);

        // when
        String encoded = cursor.encode();
        FileCursor parsed = FileCursor.parse(encoded);

        // then（URL 安全无 padding，回环无损）
        assertEquals(cursor, parsed);
    }

    @Test
    void should_buildFromOffsetDateTime_when_of_given_shanghaiTimestamp() {
        // given
        OffsetDateTime createdAt = OffsetDateTime.of(
                2026, 9, 3, 18, 15, 30, 0, ZoneOffset.ofHours(8));

        // when
        FileCursor cursor = FileCursor.of(createdAt, 7L);

        // then（内部统一为 Instant）
        assertEquals(createdAt.toInstant(), cursor.createdAt());
        assertEquals(7L, cursor.id());
    }

    @Test
    void should_returnNull_when_parse_given_blankOrIllegalCursor() {
        // given // when & then（空 / 非法游标一律视为首页）
        assertNull(FileCursor.parse(null));
        assertNull(FileCursor.parse(""));
        assertNull(FileCursor.parse("!!!not-base64!!!"));
        assertNull(FileCursor.parse(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("no-colon".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertNull(FileCursor.parse(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("not-an-instant:1".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void should_throwException_when_constructor_given_nullComponents() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new FileCursor(null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new FileCursor(Instant.now(), null));
    }
}

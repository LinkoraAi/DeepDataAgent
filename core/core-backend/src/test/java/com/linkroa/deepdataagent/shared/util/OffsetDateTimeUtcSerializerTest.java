package com.linkroa.deepdataagent.shared.util;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OffsetDateTimeUtcSerializer} RFC 3339 UTC 序列化单测（6.1 时间口径统一）。
 */
class OffsetDateTimeUtcSerializerTest {

    private final ObjectMapper mapper = buildMapper();

    private static ObjectMapper buildMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, new OffsetDateTimeUtcSerializer());
        return JsonMapper.builder().addModule(module).build();
    }

    @Test
    void should_outputUtcZ_when_serialize_given_shanghaiOffsetValue() {
        // given（+08:00 偏移输入）
        OffsetDateTime shanghai = OffsetDateTime.of(2026, 5, 18, 16, 0, 0, 0, ZoneOffset.ofHours(8));

        // when
        String json = mapper.writeValueAsString(Map.of("created_at", shanghai));

        // then（归零偏移输出 Z 形态，不泄露 +08:00）
        assertEquals("{\"created_at\":\"2026-05-18T08:00:00Z\"}", json);
    }

    @Test
    void should_keepInstant_when_serialize_given_utcValue() {
        // given
        OffsetDateTime utc = OffsetDateTime.of(2026, 5, 18, 8, 0, 0, 0, ZoneOffset.UTC);

        // when
        String json = mapper.writeValueAsString(Map.of("ts", utc));

        // then
        assertTrue(json.contains("\"2026-05-18T08:00:00Z\""));
    }

    @Test
    void should_preserveSubSecondPrecision_when_serialize_given_microTimestamp() {
        // given（DB TIMESTAMPTZ 微秒精度）
        OffsetDateTime micros = OffsetDateTime.of(2026, 5, 18, 16, 0, 0, 123_456_000, ZoneOffset.ofHours(8));

        // when
        String json = mapper.writeValueAsString(Map.of("ts", micros));

        // then（RFC 3339 允许小数秒，仍为 UTC Z 口径）
        assertTrue(json.contains("2026-05-18T08:00:00.123456Z"));
    }
}

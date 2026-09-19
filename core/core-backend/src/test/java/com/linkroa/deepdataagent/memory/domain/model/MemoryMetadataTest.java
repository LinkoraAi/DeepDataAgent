package com.linkroa.deepdataagent.memory.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryMetadata} 记忆元数据值对象不变量单测。
 */
class MemoryMetadataTest {

    @Test
    void should_returnEmptyEntries_when_empty_given_noArgs() {
        // given // when
        MemoryMetadata metadata = MemoryMetadata.empty();

        // then
        assertTrue(metadata.entries().isEmpty());
    }

    @Test
    void should_normalizeEmpty_when_of_given_nullMap() {
        // given // when
        MemoryMetadata metadata = MemoryMetadata.of(null);

        // then
        assertTrue(metadata.entries().isEmpty());
    }

    @Test
    void should_defensivelyCopy_when_of_given_mutableSourceMap() {
        // given
        Map<String, String> source = new HashMap<>();
        source.put("k", "v");

        // when
        MemoryMetadata metadata = MemoryMetadata.of(source);
        source.put("k2", "v2");

        // then
        assertEquals(1, metadata.entries().size());
        assertEquals("v", metadata.entries().get("k"));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_moreThan16Pairs() {
        // given
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            values.put("key" + i, "v");
        }

        // when // then
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetadata(values));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_blankKey() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryMetadata(Map.of(" ", "v")));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_keyExceeds64Chars() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryMetadata(Map.of("k".repeat(65), "v")));
    }

    @Test
    void should_throwIllegalArgumentException_when_construct_given_valueExceeds512Chars() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryMetadata(Map.of("k", "v".repeat(513))));
    }

    @Test
    void should_roundTrip_when_toJsonAndFromJson_given_populatedEntries() {
        // given
        MemoryMetadata metadata = MemoryMetadata.of(Map.of("source", "chat", "tag", "用户偏好"));

        // when
        String json = metadata.toJson();
        MemoryMetadata restored = MemoryMetadata.fromJson(json);

        // then
        assertEquals(metadata.entries(), restored.entries());
    }

    @Test
    void should_returnEmpty_when_fromJson_given_blankInput() {
        // given // when // then
        assertTrue(MemoryMetadata.fromJson(null).entries().isEmpty());
        assertTrue(MemoryMetadata.fromJson(" ").entries().isEmpty());
    }
}

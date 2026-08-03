package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JacksonJsonAdapter 单元测试
 * <p>测试 JSON 序列化/反序列化端口的 Jackson 实现。</p>
 */
class JacksonJsonAdapterTest {

    private JacksonJsonAdapter adapter;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        adapter = new JacksonJsonAdapter(objectMapper);
    }

    @Test
    void should_returnJsonString_when_toJson_given_object() {
        // given
        Map<String, Object> data = Map.of("key", "value", "number", 42);

        // when
        String json = adapter.toJson(data);

        // then
        assertNotNull(json);
        assertTrue(json.contains("key"));
        assertTrue(json.contains("value"));
        assertTrue(json.contains("42"));
    }

    @Test
    void should_returnObject_when_fromJson_given_validJson() {
        // given
        String json = "{\"key\":\"value\",\"number\":42}";
        Class<Map> clazz = Map.class;

        // when
        Map<String, Object> result = adapter.fromJson(json, clazz);

        // then
        assertNotNull(result);
        assertEquals("value", result.get("key"));
        assertEquals(42, result.get("number"));
    }

    @Test
    void should_throwException_when_fromJson_given_invalidJson() {
        // given
        String invalidJson = "not a json";
        Class<Map> clazz = Map.class;

        // when & then
        assertThrows(RuntimeException.class, () -> adapter.fromJson(invalidJson, clazz));
    }

    @Test
    void should_handleNull_when_toJson_given_null() {
        // when
        String json = adapter.toJson(null);

        // then
        assertEquals("null", json);
    }

    @Test
    void should_throwException_when_fromJson_given_nullJson() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> adapter.fromJson(null, Map.class));
    }
}

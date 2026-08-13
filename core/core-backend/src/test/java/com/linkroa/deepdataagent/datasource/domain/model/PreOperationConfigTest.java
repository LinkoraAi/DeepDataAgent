package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreOperationConfigTest {

    @Test
    void should_createConfig_when_enabledWithValidFields() {
        PreOperationConfig config = new PreOperationConfig(
                true,
                "https://api.example.com/auth",
                HttpMethod.POST,
                Map.of("Content-Type", "application/json"),
                Map.of("client_id", "test"),
                "{\"grant_type\":\"client_credentials\"}",
                BodyType.JSON,
                List.of(new ParamMapping("token", "header", "$.access_token"))
        );

        assertTrue(config.enabled());
        assertEquals("https://api.example.com/auth", config.url());
        assertEquals(HttpMethod.POST, config.method());
        assertEquals(BodyType.JSON, config.bodyType());
        assertEquals(1, config.paramMappings().size());
    }

    @Test
    void should_createConfig_when_disabledWithNullFields() {
        PreOperationConfig config = new PreOperationConfig(false, null, null, null, null, null, null, null);

        assertFalse(config.enabled());
        assertNull(config.url());
        assertNull(config.method());
    }

    @Test
    void should_throwException_when_enabledWithBlankUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, "", HttpMethod.GET, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, "   ", HttpMethod.GET, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, null, HttpMethod.GET, null, null, null, null, null));
    }

    @Test
    void should_throwException_when_enabledWithInvalidUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, "not-a-url", HttpMethod.GET, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, "://missing-scheme", HttpMethod.GET, null, null, null, null, null));
    }

    @Test
    void should_throwException_when_enabledWithNullMethod() {
        assertThrows(IllegalArgumentException.class, () ->
                new PreOperationConfig(true, "https://api.example.com", null, null, null, null, null, null));
    }

    @Test
    void should_createConfig_when_enabledWithGetMethod() {
        PreOperationConfig config = new PreOperationConfig(
                true, "https://api.example.com/token", HttpMethod.GET, null, null, null, null, null);

        assertEquals(HttpMethod.GET, config.method());
    }

    @Test
    void should_createConfig_when_enabledWithPostMethod() {
        PreOperationConfig config = new PreOperationConfig(
                true, "https://api.example.com/token", HttpMethod.POST, null, null, null, null, null);

        assertEquals(HttpMethod.POST, config.method());
    }

    @Test
    void should_createConfig_when_enabledWithValidUrl() {
        assertDoesNotThrow(() ->
                new PreOperationConfig(true, "https://api.example.com/auth", HttpMethod.GET, null, null, null, null, null));
        assertDoesNotThrow(() ->
                new PreOperationConfig(true, "http://localhost:8080/token", HttpMethod.POST, null, null, null, null, null));
    }

    @Test
    void should_createConfig_when_enabledWithEmptyParamMappings() {
        PreOperationConfig config = new PreOperationConfig(
                true, "https://api.example.com/auth", HttpMethod.GET, null, null, null, null, List.of());

        assertTrue(config.paramMappings().isEmpty());
    }

    @Test
    void should_createConfig_when_enabledWithMultipleParamMappings() {
        List<ParamMapping> mappings = List.of(
                new ParamMapping("token", "header", "$.access_token"),
                new ParamMapping("sessionId", "query", "$.session.id"),
                new ParamMapping("apiKey", "body", "$.key")
        );
        PreOperationConfig config = new PreOperationConfig(
                true, "https://api.example.com/auth", HttpMethod.POST, null, null, null, BodyType.JSON, mappings);

        assertEquals(3, config.paramMappings().size());
    }
}

package com.linkroa.deepdataagent.datasource.controller.request;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpdateApiSchemaRequestTest {

    @Test
    void should_createRequest_when_constructor_given_validInput() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user", "pass");
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest("PAGE_BASED", "page", "size", "$.total", 20, 100);
        ApiFieldRequest field = new ApiFieldRequest("id", "ID", "$.id", "NUMBER", "desc");

        UpdateApiSchemaRequest request = new UpdateApiSchemaRequest(
                1L, "weather", "https://api.weather.com", "GET",
                Map.of("Accept", "application/json"), Map.of("city", "beijing"),
                null, "JSON",
                "$.data", 30, 3,
                authConfig, paginationConfig, null, List.of(field)
        );

        assertEquals(1L, request.schemaId());
        assertEquals("weather", request.name());
        assertEquals("https://api.weather.com", request.url());
        assertEquals("$.data", request.jsonPathConfig());
        assertEquals(30, request.timeout());
        assertEquals(3, request.retryCount());
        assertNotNull(request.authConfig());
        assertNotNull(request.paginationConfig());
        assertEquals(1, request.fields().size());
    }

    @Test
    void should_createRequest_when_constructor_given_minimalInput() {
        UpdateApiSchemaRequest request = new UpdateApiSchemaRequest(
                1L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );

        assertEquals(1L, request.schemaId());
        assertNull(request.name());
        assertNull(request.url());
        assertNull(request.jsonPathConfig());
        assertNull(request.timeout());
        assertNull(request.authConfig());
        assertNull(request.paginationConfig());
        assertNull(request.fields());
    }
}

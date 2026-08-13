package com.linkroa.deepdataagent.datasource.controller.request;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiSchemaRequestTest {

    @Test
    void should_createApiSchemaRequest_when_constructor_given_validInput() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user", "pass");
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest("PAGE_BASED", "page", "size", "$.total", 20, 100);
        ApiFieldRequest field = new ApiFieldRequest("id", "ID", "$.id", "NUMBER", "desc");

        ApiSchemaRequest request = new ApiSchemaRequest(
                "users", "http://api.example.com", "GET",
                Map.of("Accept", "application/json"), Map.of("page", "1"),
                "{}", "JSON", "$.data", 30, 3,
                authConfig, paginationConfig, null, List.of(field)
        );

        assertEquals("users", request.name());
        assertEquals("http://api.example.com", request.url());
        assertEquals("GET", request.method());
        assertEquals(Map.of("Accept", "application/json"), request.headers());
        assertEquals(Map.of("page", "1"), request.params());
        assertEquals("{}", request.body());
        assertEquals("JSON", request.bodyType());
        assertEquals("$.data", request.jsonPathConfig());
        assertEquals(30, request.timeout());
        assertEquals(3, request.retryCount());
        assertNotNull(request.authConfig());
        assertNotNull(request.paginationConfig());
        assertEquals(1, request.fields().size());
    }

    @Test
    void should_createApiSchemaRequest_when_constructor_given_minimalInput() {
        ApiSchemaRequest request = new ApiSchemaRequest(
                "schema", "http://example.com", "POST",
                null, null, null, null, null, null, null,
                null, null, null, null
        );

        assertEquals("schema", request.name());
        assertEquals("http://example.com", request.url());
        assertEquals("POST", request.method());
        assertNull(request.headers());
        assertNull(request.params());
        assertNull(request.body());
        assertNull(request.authConfig());
        assertNull(request.paginationConfig());
        assertNull(request.fields());
    }
}

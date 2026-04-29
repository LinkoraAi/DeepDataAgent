package com.linkroa.deepdataagent.datasource.controller.request;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestPreOperationRequestTest {

    @Test
    void should_createRequest_when_constructor_given_validInput() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user", "pass");
        TestPreOperationRequest request = new TestPreOperationRequest(
                "http://api.example.com/token", "POST",
                Map.of("Content-Type", "application/json"), Map.of("grant_type", "client_credentials"),
                "{\"client_id\":\"test\"}", "JSON", authConfig
        );

        assertEquals("http://api.example.com/token", request.url());
        assertEquals("POST", request.method());
        assertEquals(Map.of("Content-Type", "application/json"), request.headers());
        assertEquals(Map.of("grant_type", "client_credentials"), request.params());
        assertEquals("{\"client_id\":\"test\"}", request.body());
        assertEquals("JSON", request.bodyType());
        assertNotNull(request.authConfig());
    }

    @Test
    void should_createRequest_when_constructor_given_minimalInput() {
        TestPreOperationRequest request = new TestPreOperationRequest(
                "http://example.com", "GET",
                null, null, null, null, null
        );

        assertEquals("http://example.com", request.url());
        assertEquals("GET", request.method());
        assertNull(request.headers());
        assertNull(request.authConfig());
    }
}

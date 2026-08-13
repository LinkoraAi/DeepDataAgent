package com.linkroa.deepdataagent.datasource.controller.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreateApiSchemaRequestTest {

    @Test
    void should_createRequest_when_constructor_given_validInput() {
        ApiSchemaRequest schema = new ApiSchemaRequest(
                "users", "http://api.example.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );
        CreateApiSchemaRequest request = new CreateApiSchemaRequest(1L, schema);

        assertEquals(1L, request.connectionId());
        assertNotNull(request.schema());
        assertEquals("users", request.schema().name());
    }

    @Test
    void should_createRequest_when_constructor_given_nullSchema() {
        CreateApiSchemaRequest request = new CreateApiSchemaRequest(1L, null);

        assertEquals(1L, request.connectionId());
        assertNull(request.schema());
    }
}

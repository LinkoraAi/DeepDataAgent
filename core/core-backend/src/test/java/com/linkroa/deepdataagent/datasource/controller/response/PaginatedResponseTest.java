package com.linkroa.deepdataagent.datasource.controller.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginatedResponseTest {

    @Test
    void should_createResponse_when_constructor_given_allFields() {
        PaginatedResponse<String> response = new PaginatedResponse<>(List.of("a", "b"), 100L, 0, 20);
        assertEquals(2, response.data().size());
        assertEquals(100L, response.total());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
    }

    @Test
    void should_createEmptyResponse_when_constructor_given_emptyList() {
        PaginatedResponse<String> response = new PaginatedResponse<>(List.of(), 0L, 0, 20);
        assertTrue(response.data().isEmpty());
        assertEquals(0L, response.total());
    }
}

package com.linkroa.deepdataagent.shared.result;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页响应共享类型单测
 */
class PaginatedResponseTest {

    @Test
    void should_holdPagingFields_when_construct_given_fullList() {
        // given
        List<String> items = List.of("a", "b");

        // when
        PaginatedResponse<String> response = new PaginatedResponse<>(items, 100L, 0, 20);

        // then
        assertEquals(items, response.list());
        assertEquals(100L, response.total());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
    }

    @Test
    void should_holdEmptyList_when_construct_given_emptyList() {
        // given
        List<String> empty = List.of();

        // when
        PaginatedResponse<String> response = new PaginatedResponse<>(empty, 0L, 0, 20);

        // then
        assertTrue(response.list().isEmpty());
        assertEquals(0L, response.total());
    }
}
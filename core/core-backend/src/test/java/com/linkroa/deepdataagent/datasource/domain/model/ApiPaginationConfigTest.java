package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiPaginationConfigTest {

    @Test
    void should_createConfig_when_pageBasedWithValidFields() {
        ApiPaginationConfig config = new ApiPaginationConfig(
                ApiPaginationType.PAGE_BASED, "page", "size", "$.total", 20, 100);

        assertEquals(ApiPaginationType.PAGE_BASED, config.paginationType());
        assertEquals("page", config.pageParamName());
        assertEquals("size", config.sizeParamName());
        assertEquals("$.total", config.totalCountJsonPath());
        assertEquals(20, config.pageSize());
        assertEquals(100, config.maxPages());
    }

    @Test
    void should_createConfig_when_noneType() {
        ApiPaginationConfig config = new ApiPaginationConfig(
                ApiPaginationType.NONE, null, null, null, null, null);

        assertEquals(ApiPaginationType.NONE, config.paginationType());
    }

    @Test
    void should_throwException_when_nullPaginationType() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(null, null, null, null, null, null));
    }

    @Test
    void should_throwException_when_pageBasedWithBlankPageParamName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "", "size", null, 20, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "   ", "size", null, 20, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, null, "size", null, 20, 100));
    }

    @Test
    void should_throwException_when_pageBasedWithBlankSizeParamName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "page", "", null, 20, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "page", null, null, 20, 100));
    }

    @Test
    void should_throwException_when_pageSizeTooSmall() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, 0, null));
    }

    @Test
    void should_throwException_when_pageSizeTooLarge() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, 501, null));
    }

    @Test
    void should_throwException_when_maxPagesTooSmall() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, null, 0));
    }

    @Test
    void should_throwException_when_maxPagesTooLarge() {
        assertThrows(IllegalArgumentException.class, () ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, null, 10001));
    }

    @Test
    void should_createConfig_when_pageSizeAtBoundary() {
        assertDoesNotThrow(() ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, 1, null));
        assertDoesNotThrow(() ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, 500, null));
    }

    @Test
    void should_createConfig_when_maxPagesAtBoundary() {
        assertDoesNotThrow(() ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, null, 1));
        assertDoesNotThrow(() ->
                new ApiPaginationConfig(ApiPaginationType.NONE, null, null, null, null, 10000));
    }

    @Test
    void should_haveOnlyNoneAndPageBased_when_checkingPaginationTypes() {
        ApiPaginationType[] types = ApiPaginationType.values();
        assertEquals(2, types.length);
        assertTrue(containsType(types, ApiPaginationType.NONE));
        assertTrue(containsType(types, ApiPaginationType.PAGE_BASED));
    }

    private boolean containsType(ApiPaginationType[] types, ApiPaginationType target) {
        for (ApiPaginationType type : types) {
            if (type == target) return true;
        }
        return false;
    }
}

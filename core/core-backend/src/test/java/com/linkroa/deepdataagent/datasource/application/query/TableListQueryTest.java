package com.linkroa.deepdataagent.datasource.application.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TableListQueryTest {

    @Test
    void should_createQuery_when_validParams_given_normalInput() {
        TableListQuery query = new TableListQuery(1L, "user", 1, 50);
        assertEquals(1L, query.connectionId());
        assertEquals("user", query.keyword());
        assertEquals(1, query.page());
        assertEquals(50, query.size());
    }

    @Test
    void should_resetPage_when_negativePage_given_negativePage() {
        TableListQuery query = new TableListQuery(1L, null, -1, 50);
        assertEquals(1, query.page());
    }

    @Test
    void should_resetPage_when_zeroPage_given_zeroPage() {
        TableListQuery query = new TableListQuery(1L, null, 0, 50);
        assertEquals(1, query.page());
    }

    @Test
    void should_resetSize_when_zeroOrNegativeSize_given_zeroSize() {
        TableListQuery query = new TableListQuery(1L, null, 1, 0);
        assertEquals(50, query.size());
    }

    @Test
    void should_resetSize_when_zeroOrNegativeSize_given_negativeSize() {
        TableListQuery query = new TableListQuery(1L, null, 1, -10);
        assertEquals(50, query.size());
    }

    @Test
    void should_allowNullKeyword_when_nullGiven_given_nullKeyword() {
        TableListQuery query = new TableListQuery(1L, null, 1, 50);
        assertNull(query.keyword());
    }
}

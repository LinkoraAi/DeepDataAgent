package com.linkroa.deepdataagent.datasource.application.query;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ListDatasourceQueryTest {

    @Test
    void should_createQuery_when_validParams_given_normalInput() {
        ListDatasourceQuery query = new ListDatasourceQuery("test", DatasourceType.JDBC, DatasourceStatus.ENABLED, 1, 20);
        assertEquals("test", query.keyword());
        assertEquals(DatasourceType.JDBC, query.type());
        assertEquals(DatasourceStatus.ENABLED, query.status());
        assertEquals(1, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void should_resetPage_when_negativePage_given_negativePage() {
        ListDatasourceQuery query = new ListDatasourceQuery(null, null, null, -1, 20);
        assertEquals(1, query.page());
    }

    @Test
    void should_resetPage_when_zeroPage_given_zeroPage() {
        ListDatasourceQuery query = new ListDatasourceQuery(null, null, null, 0, 20);
        assertEquals(1, query.page());
    }

    @Test
    void should_resetSize_when_zeroOrNegativeSize_given_zeroSize() {
        ListDatasourceQuery query = new ListDatasourceQuery(null, null, null, 1, 0);
        assertEquals(20, query.size());
    }

    @Test
    void should_resetSize_when_zeroOrNegativeSize_given_negativeSize() {
        ListDatasourceQuery query = new ListDatasourceQuery(null, null, null, 1, -5);
        assertEquals(20, query.size());
    }

    @Test
    void should_allowNullFields_when_allNull_given_nullParams() {
        ListDatasourceQuery query = new ListDatasourceQuery(null, null, null, 1, 10);
        assertNull(query.keyword());
        assertNull(query.type());
        assertNull(query.status());
    }
}

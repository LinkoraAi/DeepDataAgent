package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiPaginationTypeTest {

    @Test
    void should_containAllTypes_when_values_given_called() {
        ApiPaginationType[] values = ApiPaginationType.values();
        assertEquals(3, values.length);
    }

    @Test
    void should_returnType_when_valueOf_given_validName() {
        assertEquals(ApiPaginationType.NONE, ApiPaginationType.valueOf("NONE"));
        assertEquals(ApiPaginationType.PAGE_BASED, ApiPaginationType.valueOf("PAGE_BASED"));
        assertEquals(ApiPaginationType.CURSOR_BASED, ApiPaginationType.valueOf("CURSOR_BASED"));
    }
}

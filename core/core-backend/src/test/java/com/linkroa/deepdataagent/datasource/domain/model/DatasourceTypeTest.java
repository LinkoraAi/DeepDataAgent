package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceTypeTest {

    @Test
    void should_containJdbcAndApi_when_values_given_called() {
        DatasourceType[] values = DatasourceType.values();
        assertEquals(2, values.length);
        assertNotNull(DatasourceType.valueOf("JDBC"));
        assertNotNull(DatasourceType.valueOf("API"));
    }

    @Test
    void should_returnJdbc_when_valueOf_given_JDBC() {
        assertEquals(DatasourceType.JDBC, DatasourceType.valueOf("JDBC"));
    }

    @Test
    void should_returnApi_when_valueOf_given_API() {
        assertEquals(DatasourceType.API, DatasourceType.valueOf("API"));
    }

    @Test
    void should_throwException_when_valueOf_given_invalidType() {
        assertThrows(IllegalArgumentException.class, () -> DatasourceType.valueOf("INVALID"));
    }
}

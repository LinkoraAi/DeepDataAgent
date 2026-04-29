package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTypeTest {

    @Test
    void should_containAllTypes_when_values_given_called() {
        JdbcType[] values = JdbcType.values();
        assertEquals(2, values.length);
    }

    @Test
    void should_returnCorrectDefaultPort_when_getDefaultPort_given_eachType() {
        assertEquals(3306, JdbcType.MYSQL.getDefaultPort());
        assertEquals(8123, JdbcType.CLICKHOUSE.getDefaultPort());
    }

    @Test
    void should_returnType_when_valueOf_given_validName() {
        assertEquals(JdbcType.MYSQL, JdbcType.valueOf("MYSQL"));
        assertEquals(JdbcType.CLICKHOUSE, JdbcType.valueOf("CLICKHOUSE"));
    }
}

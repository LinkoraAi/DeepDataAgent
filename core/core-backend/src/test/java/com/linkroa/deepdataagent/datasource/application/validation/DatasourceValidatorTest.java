package com.linkroa.deepdataagent.datasource.application.validation;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceValidatorTest {

    @Test
    void should_parseDatasourceType_when_valueIsValid() {
        assertEquals(DatasourceType.JDBC, DatasourceValidator.parseDatasourceType("JDBC"));
        assertEquals(DatasourceType.API, DatasourceValidator.parseDatasourceType("API"));
    }

    @Test
    void should_returnNull_when_parseDatasourceTypeOrNull_given_blankValue() {
        assertNull(DatasourceValidator.parseDatasourceTypeOrNull(""));
        assertNull(DatasourceValidator.parseDatasourceTypeOrNull("   "));
        assertNull(DatasourceValidator.parseDatasourceTypeOrNull(null));
    }

    @Test
    void should_parseJdbcType_when_valueIsValid() {
        assertEquals(JdbcType.MYSQL, DatasourceValidator.parseJdbcType("MYSQL"));
        assertEquals(JdbcType.CLICKHOUSE, DatasourceValidator.parseJdbcType("CLICKHOUSE"));
    }

    @Test
    void should_returnNull_when_parseJdbcType_given_blankValue() {
        assertNull(DatasourceValidator.parseJdbcType(""));
        assertNull(DatasourceValidator.parseJdbcType("   "));
        assertNull(DatasourceValidator.parseJdbcType(null));
    }

    @Test
    void should_parseHttpMethod_when_valueIsValid() {
        assertEquals(HttpMethod.GET, DatasourceValidator.parseHttpMethod("GET"));
        assertEquals(HttpMethod.POST, DatasourceValidator.parseHttpMethod("POST"));
    }

    @Test
    void should_returnNull_when_parseHttpMethod_given_blankValue() {
        assertNull(DatasourceValidator.parseHttpMethod(""));
        assertNull(DatasourceValidator.parseHttpMethod("   "));
        assertNull(DatasourceValidator.parseHttpMethod(null));
    }

    @Test
    void should_parseDatasourceStatus_when_valueIsValid() {
        assertEquals(DatasourceStatus.ENABLED, DatasourceValidator.parseDatasourceStatusOrNull("ENABLED"));
        assertEquals(DatasourceStatus.DISABLED, DatasourceValidator.parseDatasourceStatusOrNull("DISABLED"));
    }

    @Test
    void should_returnNull_when_parseDatasourceStatusOrNull_given_blankValue() {
        assertNull(DatasourceValidator.parseDatasourceStatusOrNull(""));
        assertNull(DatasourceValidator.parseDatasourceStatusOrNull("   "));
        assertNull(DatasourceValidator.parseDatasourceStatusOrNull(null));
    }

    @Test
    void should_pass_when_validatePostgresqlSchema_given_nonPgType() {
        // then 非 PostgreSQL 类型不做校验
        assertDoesNotThrow(() -> DatasourceValidator.validatePostgresqlSchema(JdbcType.MYSQL, "public,other"));
        assertDoesNotThrow(() -> DatasourceValidator.validatePostgresqlSchema(JdbcType.CLICKHOUSE, null));
    }

    @Test
    void should_pass_when_validatePostgresqlSchema_given_blankOrCallbackSchema() {
        // then 空 schema 走默认 public，不校验
        assertDoesNotThrow(() -> DatasourceValidator.validatePostgresqlSchema(JdbcType.POSTGRESQL, null));
        assertDoesNotThrow(() -> DatasourceValidator.validatePostgresqlSchema(JdbcType.POSTGRESQL, "  "));
        assertDoesNotThrow(() -> DatasourceValidator.validatePostgresqlSchema(JdbcType.POSTGRESQL, "public"));
    }

    @Test
    void should_reject_when_validatePostgresqlSchema_given_multipleValues() {
        // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DatasourceValidator.validatePostgresqlSchema(JdbcType.POSTGRESQL, "public,analytics"));
        assertTrue(ex.getMessage().contains("仅支持单个 schema"));
    }

    @Test
    void should_reject_when_validatePostgresqlSchema_given_builtinSchema() {
        // then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> DatasourceValidator.validatePostgresqlSchema(JdbcType.POSTGRESQL, "pg_catalog"));
        assertTrue(ex.getMessage().contains("内置 schema"));
    }
}

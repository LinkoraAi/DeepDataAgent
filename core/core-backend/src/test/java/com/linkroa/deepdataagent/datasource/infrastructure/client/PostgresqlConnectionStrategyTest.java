package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 连接策略单元测试
 */
class PostgresqlConnectionStrategyTest {

    private final PostgresqlConnectionStrategy strategy = new PostgresqlConnectionStrategy();

    @Test
    void should_buildJdbcUrlWithPublicSchema_when_buildJdbcUrl_given_defaultSchema() {
        // given
        DatasourceConnection connection = buildConnection("public");

        // when
        String url = strategy.buildJdbcUrl(connection);

        // then
        assertEquals("jdbc:postgresql://localhost:5432/testdb?currentSchema=public", url);
    }

    @Test
    void should_buildJdbcUrlWithCustomSchema_when_buildJdbcUrl_given_customSchema() {
        // given
        DatasourceConnection connection = buildConnection("analytics");

        // when
        String url = strategy.buildJdbcUrl(connection);

        // then
        assertEquals("jdbc:postgresql://localhost:5432/testdb?currentSchema=analytics", url);
    }

    @Test
    void should_returnDriverClass_when_getDriverClassName_given_instance() {
        // when
        String driverClass = strategy.getDriverClassName();

        // then
        assertEquals("org.postgresql.Driver", driverClass);
    }

    @Test
    void should_buildSqlWithSchema_when_buildPreviewSql_given_validSchemaAndTable() {
        // when
        String sql = strategy.buildPreviewSql("public", "users", 100);

        // then
        assertEquals("SELECT * FROM \"public\".\"users\" LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithoutSchema_when_buildPreviewSql_given_nullSchema() {
        // when
        String sql = strategy.buildPreviewSql(null, "users", 100);

        // then
        assertEquals("SELECT * FROM \"users\" LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithoutSchema_when_buildPreviewSql_given_blankSchema() {
        // when
        String sql = strategy.buildPreviewSql("   ", "users", 100);

        // then
        assertEquals("SELECT * FROM \"users\" LIMIT 100", sql);
    }

    @Test
    void should_extractConfiguredSchema_when_extractSchemas_given_pgConnection() {
        // given
        DatasourceConnection connection = buildConnection("analytics");

        // when
        List<DatabaseSchema> schemas = strategy.extractSchemas(connection);

        // then 使用配置的 schema 而非 database 名
        assertEquals(1, schemas.size());
        assertEquals("analytics", schemas.get(0).schemaName());
    }

    @Test
    void should_extractPublicSchema_when_extractSchemas_given_defaultSchema() {
        // given
        DatasourceConnection connection = buildConnection("public");

        // when
        List<DatabaseSchema> schemas = strategy.extractSchemas(connection);

        // then
        assertEquals("public", schemas.get(0).schemaName());
    }

    @Test
    void should_returnDatabaseAsCatalog_when_getCatalogName_given_pgConnection() {
        // given
        DatasourceConnection connection = buildConnection("public");

        // when
        String catalog = strategy.getCatalogName(connection);

        // then catalog 为数据库名，与 schema 区分
        assertEquals("testdb", catalog);
    }

    @Test
    void should_keepPrimitive_when_convertPreviewValue_given_primitiveValue() {
        // when
        Object result = strategy.convertPreviewValue("2026-01-01");

        // then
        assertEquals("2026-01-01", result);
    }

    @Test
    void should_convertCompositeToText_when_convertPreviewValue_given_compositeValue() {
        // given
        Object composite = new StringBuilder("{\"k\":\"v\"}");

        // when
        Object result = strategy.convertPreviewValue(composite);

        // then
        assertTrue(result instanceof String);
        assertEquals("{\"k\":\"v\"}", result);
    }

    private DatasourceConnection buildConnection(String schema) {
        JdbcConnectionConfig config = new JdbcConnectionConfig("localhost", 5432, "testdb", "root", "pass", schema);
        return new DatasourceConnection(
                null, "test-pg", DatasourceType.JDBC, JdbcType.POSTGRESQL, DatasourceStatus.ENABLED,
                config, null, null, null, null, null
        );
    }
}
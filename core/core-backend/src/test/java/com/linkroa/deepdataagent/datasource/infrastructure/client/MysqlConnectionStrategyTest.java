package com.linkroa.deepdataagent.datasource.infrastructure.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MysqlConnectionStrategyTest {

    private MysqlConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MysqlConnectionStrategy();
    }

    @Test
    void should_buildSqlWithSchema_when_buildPreviewSql_given_validSchemaAndTable() {
        String sql = strategy.buildPreviewSql("testdb", "users", 100);

        assertEquals("SELECT * FROM `testdb`.`users` LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithoutSchema_when_buildPreviewSql_given_nullSchema() {
        String sql = strategy.buildPreviewSql(null, "users", 100);

        assertEquals("SELECT * FROM `users` LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithBlankSchema_when_buildPreviewSql_given_blankSchema() {
        String sql = strategy.buildPreviewSql("   ", "users", 100);

        assertEquals("SELECT * FROM `users` LIMIT 100", sql);
    }

    @Test
    void should_escapeBacktick_when_quoteIdentifier_given_identifierWithBacktick() {
        String sql = strategy.buildPreviewSql("test`db", "user`table", 10);

        assertEquals("SELECT * FROM `test``db`.`user``table` LIMIT 10", sql);
    }

    @Test
    void should_returnDriverClass_when_getDriverClassName_given_instance() {
        assertEquals("com.mysql.cj.jdbc.Driver", strategy.getDriverClassName());
    }
}

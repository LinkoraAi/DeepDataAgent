package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AbstractJdbcConnectionStrategyTest {

    private TestJdbcStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TestJdbcStrategy();
    }

    @Test
    void should_buildSqlWithSchema_when_buildPreviewSql_given_validSchemaAndTable() {
        String sql = strategy.buildPreviewSql("testdb", "users", 100);

        assertEquals("SELECT * FROM \"testdb\".\"users\" LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithoutSchema_when_buildPreviewSql_given_nullSchema() {
        String sql = strategy.buildPreviewSql(null, "users", 100);

        assertEquals("SELECT * FROM \"users\" LIMIT 100", sql);
    }

    @Test
    void should_buildSqlWithoutSchema_when_buildPreviewSql_given_blankSchema() {
        String sql = strategy.buildPreviewSql("   ", "users", 100);

        assertEquals("SELECT * FROM \"users\" LIMIT 100", sql);
    }

    @Test
    void should_quoteIdentifier_when_quoteIdentifier_given_normalIdentifier() {
        String quoted = strategy.quoteIdentifier("table_name");

        assertEquals("\"table_name\"", quoted);
    }

    @Test
    void should_returnEmpty_when_quoteIdentifier_given_nullIdentifier() {
        String quoted = strategy.quoteIdentifier(null);

        assertEquals("", quoted);
    }

    @Test
    void should_escapeDoubleQuote_when_quoteIdentifier_given_identifierWithQuote() {
        String quoted = strategy.quoteIdentifier("table\"name");

        assertEquals("\"table\"\"name\"", quoted);
    }

    private static class TestJdbcStrategy extends AbstractJdbcConnectionStrategy {
        @Override
        public String buildJdbcUrl(DatasourceConnection connection) {
            return "jdbc:test://localhost:3306/test";
        }

        @Override
        public String getDriverClassName() {
            return "com.test.jdbc.Driver";
        }

        @Override
        protected String buildPreviewSql(String schemaName, String tableName, int limit) {
            StringBuilder sql = new StringBuilder("SELECT * FROM ");
            if (schemaName != null && !schemaName.isBlank()) {
                sql.append(quoteIdentifier(schemaName)).append(".");
            }
            sql.append(quoteIdentifier(tableName));
            sql.append(" LIMIT ").append(limit);
            return sql.toString();
        }
    }
}

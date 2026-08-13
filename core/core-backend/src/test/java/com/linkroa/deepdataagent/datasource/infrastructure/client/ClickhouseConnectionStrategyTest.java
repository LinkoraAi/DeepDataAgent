package com.linkroa.deepdataagent.datasource.infrastructure.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClickhouseConnectionStrategyTest {

    private ClickhouseConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ClickhouseConnectionStrategy();
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
        assertEquals("com.clickhouse.jdbc.ClickHouseDriver", strategy.getDriverClassName());
    }

    @Test
    void should_returnTableComment_when_extractTableComment_given_validTable() throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement("SELECT comment FROM system.tables WHERE database = ? AND name = ?")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("comment")).thenReturn("ClickHouse table comment");

        String comment = strategy.extractTableComment(conn, "testdb", "users");

        assertEquals("ClickHouse table comment", comment);
        verify(stmt).setString(1, "testdb");
        verify(stmt).setString(2, "users");
    }

    @Test
    void should_returnNull_when_extractTableComment_given_noResult() throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement("SELECT comment FROM system.tables WHERE database = ? AND name = ?")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        String comment = strategy.extractTableComment(conn, "testdb", "users");

        assertNull(comment);
    }

    @Test
    void should_returnNull_when_extractTableComment_given_sqlException() throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);

        when(conn.prepareStatement("SELECT comment FROM system.tables WHERE database = ? AND name = ?")).thenReturn(stmt);
        when(stmt.executeQuery()).thenThrow(new SQLException("Query failed"));

        String comment = strategy.extractTableComment(conn, "testdb", "users");

        assertNull(comment);
    }

}

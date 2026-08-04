package com.linkroa.deepdataagent.agent.acl.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcConnectionInfoTest {

    @Test
    void create_shouldSucceed_whenValid() {
        JdbcConnectionInfo info = new JdbcConnectionInfo("localhost", 3306, "testdb", "user", "pass", null);

        assertEquals("localhost", info.host());
        assertEquals(3306, info.port());
        assertEquals("testdb", info.database());
        assertEquals("user", info.username());
        assertEquals("pass", info.password());
    }

    @Test
    void create_shouldThrow_whenHostIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo(null, 3306, "testdb", "user", "pass", null)
        );
    }

    @Test
    void create_shouldThrow_whenHostIsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo("   ", 3306, "testdb", "user", "pass", null)
        );
    }

    @Test
    void create_shouldThrow_whenPortIsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo("localhost", 0, "testdb", "user", "pass", null)
        );
    }

    @Test
    void create_shouldThrow_whenPortIsNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo("localhost", -1, "testdb", "user", "pass", null)
        );
    }

    @Test
    void create_shouldThrow_whenDatabaseIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo("localhost", 3306, null, "user", "pass", null)
        );
    }

    @Test
    void create_shouldThrow_whenDatabaseIsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcConnectionInfo("localhost", 3306, "   ", "user", "pass", null)
        );
    }
}

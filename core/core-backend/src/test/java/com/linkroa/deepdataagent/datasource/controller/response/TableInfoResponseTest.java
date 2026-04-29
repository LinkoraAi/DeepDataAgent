package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TableInfoResponseTest {

    @Test
    void should_mapFields_when_from_given_tableInfo() {
        TableInfo tableInfo = new TableInfo(1L, 10L, "users", "user table", null, LocalDateTime.parse("2024-01-01T00:00:00"), LocalDateTime.parse("2024-01-02T00:00:00"));

        TableInfoResponse response = TableInfoResponse.from(tableInfo);

        assertEquals(1L, response.id());
        assertEquals(10L, response.databaseSchemaId());
        assertEquals("users", response.tableName());
        assertEquals("user table", response.tableComment());
        assertEquals(LocalDateTime.parse("2024-01-01T00:00:00"), response.createdAt());
        assertEquals(LocalDateTime.parse("2024-01-02T00:00:00"), response.updatedAt());
    }

    @Test
    void should_handleNullFields_when_from_given_tableInfoWithNulls() {
        TableInfo tableInfo = new TableInfo(1L, 10L, "users", null, null, null, null);

        TableInfoResponse response = TableInfoResponse.from(tableInfo);

        assertNull(response.tableComment());
        assertNull(response.createdAt());
        assertNull(response.updatedAt());
    }
}

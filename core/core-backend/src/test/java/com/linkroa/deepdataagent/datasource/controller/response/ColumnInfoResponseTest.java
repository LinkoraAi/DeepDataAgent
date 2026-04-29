package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnInfoResponseTest {

    @Test
    void should_mapFields_when_from_given_columnInfo() {
        ColumnInfo columnInfo = new ColumnInfo(1L, 10L, "id", "INTEGER", "primary key", null, LocalDateTime.parse("2024-01-01T00:00:00"), LocalDateTime.parse("2024-01-02T00:00:00"));

        ColumnInfoResponse response = ColumnInfoResponse.from(columnInfo);

        assertEquals(1L, response.id());
        assertEquals(10L, response.tableId());
        assertEquals("id", response.columnName());
        assertEquals("INTEGER", response.dataType());
        assertEquals("primary key", response.columnComment());
        assertEquals(LocalDateTime.parse("2024-01-01T00:00:00"), response.createdAt());
        assertEquals(LocalDateTime.parse("2024-01-02T00:00:00"), response.updatedAt());
    }
}

package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TableInfoResponseTest {

    private final DatasourceResponseMapper mapper = Mappers.getMapper(DatasourceResponseMapper.class);

    @Test
    void should_mapFields_when_toTableInfoResponse_given_tableInfo() {
        TableInfo tableInfo = new TableInfo(1L, 10L, "users", "user table", null, OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), OffsetDateTime.parse("2024-01-02T00:00:00+08:00"));

        TableInfoResponse response = mapper.toTableInfoResponse(tableInfo);

        assertEquals(1L, response.id());
        assertEquals(10L, response.databaseSchemaId());
        assertEquals("users", response.tableName());
        assertEquals("user table", response.tableComment());
        assertEquals(OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), response.createdAt());
        assertEquals(OffsetDateTime.parse("2024-01-02T00:00:00+08:00"), response.updatedAt());
    }

    @Test
    void should_handleNullFields_when_toTableInfoResponse_given_tableInfoWithNulls() {
        TableInfo tableInfo = new TableInfo(1L, 10L, "users", null, null, null, null);

        TableInfoResponse response = mapper.toTableInfoResponse(tableInfo);

        assertNull(response.tableComment());
        assertNull(response.createdAt());
        assertNull(response.updatedAt());
    }
}

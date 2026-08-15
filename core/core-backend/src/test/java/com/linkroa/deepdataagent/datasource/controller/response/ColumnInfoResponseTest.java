package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnInfoResponseTest {

    private final DatasourceResponseMapper mapper = Mappers.getMapper(DatasourceResponseMapper.class);

    @Test
    void should_mapFields_when_toColumnInfoResponse_given_columnInfo() {
        ColumnInfo columnInfo = new ColumnInfo(1L, 10L, "id", "INTEGER", "primary key", null, OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), OffsetDateTime.parse("2024-01-02T00:00:00+08:00"));

        ColumnInfoResponse response = mapper.toColumnInfoResponse(columnInfo);

        assertEquals(1L, response.id());
        assertEquals(10L, response.tableId());
        assertEquals("id", response.columnName());
        assertEquals("INTEGER", response.dataType());
        assertEquals("primary key", response.columnComment());
        assertEquals(OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), response.createdAt());
        assertEquals(OffsetDateTime.parse("2024-01-02T00:00:00+08:00"), response.updatedAt());
    }
}

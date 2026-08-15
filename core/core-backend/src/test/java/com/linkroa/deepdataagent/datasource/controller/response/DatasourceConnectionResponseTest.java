package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceConnectionResponseTest {

    private final DatasourceResponseMapper mapper = Mappers.getMapper(DatasourceResponseMapper.class);

    @Test
    void should_mapJdbcFields_when_toConnectionResponse_given_jdbcConnection() {
        DatasourceConnection connection = new DatasourceConnection(
                1L, "test", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass", null),
                "desc", OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), OffsetDateTime.parse("2024-01-02T00:00:00+08:00"), "admin", null
        );

        DatasourceConnectionResponse response = mapper.toConnectionResponse(connection);

        assertEquals(1L, response.id());
        assertEquals("test", response.name());
        assertEquals("JDBC", response.type());
        assertEquals("MYSQL", response.subType());
        assertEquals("ENABLED", response.status());
        assertEquals("localhost", response.host());
        assertEquals(3306, response.port());
        assertEquals("testdb", response.database());
        assertEquals("root", response.username());
        assertEquals("desc", response.description());
        assertEquals(OffsetDateTime.parse("2024-01-01T00:00:00+08:00"), response.createdAt());
        assertEquals("admin", response.createdBy());
    }

    @Test
    void should_mapApiFields_when_toConnectionResponse_given_apiConnection() {
        DatasourceConnection connection = new DatasourceConnection(
                2L, "api-test", DatasourceType.API, null, DatasourceStatus.DISABLED,
                null, null, null, null, null, null
        );

        DatasourceConnectionResponse response = mapper.toConnectionResponse(connection);

        assertEquals(2L, response.id());
        assertEquals("API", response.type());
        assertNull(response.subType());
        assertNull(response.host());
        assertNull(response.port());
    }

    @Test
    void should_handleNullJdbcConfig_when_toConnectionResponse_given_connectionWithNullJdbcConfig() {
        DatasourceConnection connection = new DatasourceConnection(
                1L, "api-conn", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null
        );

        DatasourceConnectionResponse response = mapper.toConnectionResponse(connection);

        assertNull(response.host());
        assertNull(response.port());
        assertNull(response.database());
        assertNull(response.username());
    }
}

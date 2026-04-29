package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DatasourceAssemblerTest {

    @Test
    void should_mapJdbcFields_when_toDatasourceConnection_given_jdbcCreateCommand() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, "desc", jdbcConfig, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals("test-ds", result.name());
        assertEquals(DatasourceType.JDBC, result.type());
        assertEquals(JdbcType.MYSQL, result.subType());
        assertEquals(DatasourceStatus.ENABLED, result.status());
        assertNotNull(result.jdbcConnectionConfig());
        assertEquals("localhost", result.jdbcConnectionConfig().host());
        assertEquals(3306, result.jdbcConnectionConfig().port());
        assertEquals("testdb", result.jdbcConnectionConfig().database());
        assertEquals("desc", result.description());
    }

    @Test
    void should_useDefaultPort_when_portIsNull_given_jdbcCreateCommandWithoutPort() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals(3306, result.jdbcConnectionConfig().port());
    }

    @Test
    void should_mapApiFields_when_toDatasourceConnection_given_apiCreateCommand() {
        ApiSchemaCommand apiSchema = new ApiSchemaCommand(
                "schema1", HttpMethod.POST, "http://example.com/api",
                Map.of("X-Custom", "value"), Map.of("key", "val"), "{\"query\":\"test\"}",
                null, "$.data", 10, null,
                ApiAuthType.BASIC_AUTH, "user", "pass",
                "PAGE_BASED", "pageSize", "pageNum", "$.total", 20, 30,
                null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, "API desc", null, List.of(apiSchema)
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals("api-ds", result.name());
        assertEquals(DatasourceType.API, result.type());
        assertNull(result.jdbcConnectionConfig());
    }

    @Test
    void should_mergeFields_when_toDatasourceConnection_given_updateCommandWithPartialChanges() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-name", "new desc"
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals(1L, result.id());
        assertEquals("new-name", result.name());
        assertEquals("old-host", result.jdbcConnectionConfig().host());
        assertEquals(3306, result.jdbcConnectionConfig().port());
        assertEquals("olddb", result.jdbcConnectionConfig().database());
        assertEquals("oldpass", result.jdbcConnectionConfig().password());
        assertEquals("new desc", result.description());
    }

    @Test
    void should_keepExistingFields_when_updateCommandHasNulls_given_partialUpdate() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, null, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("old-name", result.name());
        assertEquals("old-host", result.jdbcConnectionConfig().host());
        assertEquals("olddb", result.jdbcConnectionConfig().database());
        assertEquals("old desc", result.description());
    }
}

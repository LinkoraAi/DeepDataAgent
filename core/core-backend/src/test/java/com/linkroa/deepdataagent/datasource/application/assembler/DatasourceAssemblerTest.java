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
    void should_handleNullExistingJdbcConnection_when_update_given_existingConnectionWithNullJdbcConfig() {
        // given: 现有连接没有 jdbcConnectionConfig（API 类型），更新命令提供 JDBC 配置
        DatasourceConnection existing = new DatasourceConnection(
                1L, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, "API desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "updated-ds", "updated desc",
                new JdbcConfigCommand("new-host", 3306, "newdb", "newuser", "newpass")
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("updated-ds", result.name());
        assertEquals("new-host", result.jdbcConnectionConfig().host());
        assertEquals("newdb", result.jdbcConnectionConfig().database());
        assertEquals("newuser", result.jdbcConnectionConfig().username());
        assertEquals("newpass", result.jdbcConnectionConfig().password());
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
                1L, "new-name", "new desc", null
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
                1L, null, null, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("old-name", result.name());
        assertEquals("old-host", result.jdbcConnectionConfig().host());
        assertEquals("olddb", result.jdbcConnectionConfig().database());
        assertEquals("old desc", result.description());
    }

    @Test
    void should_useProvidedPort_when_portIsNotNull_given_jdbcCreateCommand() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3307, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, "desc", jdbcConfig, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals(3307, result.jdbcConnectionConfig().port());
    }

    @Test
    void should_keepExistingPassword_when_updateCommandHasBlankPassword_given_existingConnection() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-name", "new desc",
                new JdbcConfigCommand("new-host", 3307, "newdb", "newuser", "")
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("oldpass", result.jdbcConnectionConfig().password());
        assertEquals("new-host", result.jdbcConnectionConfig().host());
    }

    @Test
    void should_useExistingPort_when_updateCommandPortIsNull_given_existingConnection() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-name", "new desc",
                new JdbcConfigCommand("new-host", null, "newdb", "newuser", "newpass")
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals(3306, result.jdbcConnectionConfig().port());
    }

    @Test
    void should_useDefaultName_when_commandNameIsBlank_given_updateCommand() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "  ", "new desc", null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("old-name", result.name());
    }

    @Test
    void should_useDefaultDescription_when_commandDescriptionIsBlank_given_updateCommand() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-name", "  ", null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("old desc", result.description());
    }

    @Test
    void should_throwException_when_toDatasourceConnection_given_jdbcTypeAndNullJdbcConfig() {
        // given: JDBC 类型但 jdbcConfig 为 null（DatasourceConnection 校验会抛出异常）
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "jdbc-ds", DatasourceType.JDBC, JdbcType.MYSQL, "desc", null, null
        );

        // when & then: 应该抛出 IllegalArgumentException，因为 JDBC 类型必须提供连接配置
        assertThrows(IllegalArgumentException.class, () ->
                DatasourceAssembler.toDatasourceConnection(command)
        );
    }

    @Test
    void should_ignoreJdbcConfig_when_toDatasourceConnection_given_apiTypeWithJdbcConfig() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, "API desc", jdbcConfig, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals("api-ds", result.name());
        assertEquals(DatasourceType.API, result.type());
        assertNull(result.jdbcConnectionConfig());
    }
}

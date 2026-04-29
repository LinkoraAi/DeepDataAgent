package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.ApiConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

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
        assertNull(result.apiConnectionConfig());
        assertNull(result.apiAuthConfig());
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
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com/api", HttpMethod.POST,
                Map.of("X-Custom", "value"), Map.of("key", "val"), "{\"query\":\"test\"}",
                ApiAuthType.BEARER_TOKEN, "my-token", null, null,
                "PAGE_BASED", "pageSize", "pageNum", null, null, "$.total", 20, 30, 10, "$.data",
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, "API desc", null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals("api-ds", result.name());
        assertEquals(DatasourceType.API, result.type());
        assertNull(result.jdbcConnectionConfig());
        assertNotNull(result.apiConnectionConfig());
        assertEquals("http://example.com/api", result.apiConnectionConfig().url());
        assertEquals(HttpMethod.POST, result.apiConnectionConfig().method());
        assertNotNull(result.apiConnectionConfig().paginationConfig());
        assertEquals(10, result.apiConnectionConfig().timeout());
        assertNotNull(result.apiAuthConfig());
        assertEquals(ApiAuthType.BEARER_TOKEN, result.apiAuthConfig().authType());
    }

    @Test
    void should_useNoAuthDefault_when_apiAuthTypeIsNull_given_apiCommandWithoutAuth() {
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com", HttpMethod.GET,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, 10, null,
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals(ApiAuthType.NO_AUTH, result.apiAuthConfig().authType());
        assertNull(result.apiConnectionConfig().paginationConfig());
    }

    @Test
    void should_useDefaultTimeout_when_apiTimeoutIsNull_given_apiCommandWithoutTimeout() {
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com", HttpMethod.GET,
                null, null, null,
                ApiAuthType.NO_AUTH, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals(10, result.apiConnectionConfig().timeout());
    }

    @Test
    void should_useDefaultMethod_when_apiMethodIsNull_given_apiCommandWithoutMethod() {
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com", null,
                null, null, null,
                ApiAuthType.NO_AUTH, null, null, null,
                null, null, null, null, null, null, null, null, 10, null,
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertEquals(HttpMethod.GET, result.apiConnectionConfig().method());
    }

    @Test
    void should_useNullPageSize_when_apiPageSizeIsNull_given_paginationWithNullSize() {
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com", HttpMethod.GET,
                null, null, null,
                ApiAuthType.NO_AUTH, null, null, null,
                "PAGE_BASED", "size", "page", null, null, "$.total", null, null, 10, null,
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertNull(result.apiConnectionConfig().paginationConfig().pageSize());
    }

    @Test
    void should_notCreatePagination_when_paginationTypeIsNone_given_apiCommandWithNonePagination() {
        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://example.com", HttpMethod.GET,
                null, null, null,
                ApiAuthType.NO_AUTH, null, null, null,
                "NONE", null, null, null, null, null, null, null, 10, null,
                null, null, null, null, null
        );
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command);

        assertNull(result.apiConnectionConfig().paginationConfig());
    }

    @Test
    void should_mergeFields_when_toDatasourceConnection_given_updateCommandWithPartialChanges() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                null, null, "old desc", null, null, null, null
        );

        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("new-host", null, null, null, "newpass");
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-name", DatasourceType.JDBC, JdbcType.MYSQL, "new desc", jdbcConfig, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals(1L, result.id());
        assertEquals("new-name", result.name());
        assertEquals("new-host", result.jdbcConnectionConfig().host());
        assertEquals(3306, result.jdbcConnectionConfig().port());
        assertEquals("olddb", result.jdbcConnectionConfig().database());
        assertEquals("newpass", result.jdbcConnectionConfig().password());
        assertEquals("new desc", result.description());
    }

    @Test
    void should_keepExistingFields_when_updateCommandHasNulls_given_partialUpdate() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "old-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("old-host", 3306, "olddb", "root", "oldpass"),
                null, null, "old desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, null, DatasourceType.JDBC, JdbcType.MYSQL, null, null, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("old-name", result.name());
        assertEquals("old-host", result.jdbcConnectionConfig().host());
        assertEquals("olddb", result.jdbcConnectionConfig().database());
        assertEquals("old desc", result.description());
    }

    @Test
    void should_mergeApiFields_when_toDatasourceConnection_given_apiUpdateCommand() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://old.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null
        );

        ApiConfigCommand apiConfig = new ApiConfigCommand(
                "http://new.com", HttpMethod.POST,
                null, null, null,
                ApiAuthType.BASIC_AUTH, null, "user", "pass",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "new-api-ds", DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("new-api-ds", result.name());
        assertEquals("http://new.com", result.apiConnectionConfig().url());
        assertEquals(HttpMethod.POST, result.apiConnectionConfig().method());
        assertEquals(ApiAuthType.BASIC_AUTH, result.apiAuthConfig().authType());
    }

    @Test
    void should_keepExistingApiFields_when_updateCommandHasNullApiFields_given_apiPartialUpdate() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://old.com", HttpMethod.GET, Map.of("h", "v"), null, "body", null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.BEARER_TOKEN, "u", "p", "tok"),
                "desc", null, null, null, null
        );

        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, null, DatasourceType.API, null, null, null, null
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertEquals("api-ds", result.name());
        assertEquals("http://old.com", result.apiConnectionConfig().url());
        assertEquals(HttpMethod.GET, result.apiConnectionConfig().method());
        assertEquals("body", result.apiConnectionConfig().body());
        assertEquals(ApiAuthType.BEARER_TOKEN, result.apiAuthConfig().authType());
        assertEquals("tok", result.apiAuthConfig().token());
    }

    @Test
    void should_createPaginationConfig_when_updateCommandHasPagination_given_apiUpdateWithPagination() {
        DatasourceConnection existing = new DatasourceConnection(
                1L, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://old.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null
        );

        ApiConfigCommand apiConfig = new ApiConfigCommand(
                null, null,
                null, null, null,
                null, null, null, null,
                "CURSOR_BASED", "cursor", null, "cursorParam", "$.cursor", "$.total", 50, null, null, null,
                null, null, null, null, null
        );
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, null, DatasourceType.API, null, null, null, apiConfig
        );

        DatasourceConnection result = DatasourceAssembler.toDatasourceConnection(command, existing);

        assertNotNull(result.apiConnectionConfig().paginationConfig());
        assertEquals(ApiPaginationType.CURSOR_BASED, result.apiConnectionConfig().paginationConfig().paginationType());
        assertEquals(50, result.apiConnectionConfig().paginationConfig().pageSize());
    }
}

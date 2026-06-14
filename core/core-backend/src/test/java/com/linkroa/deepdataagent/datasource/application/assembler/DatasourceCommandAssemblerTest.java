package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceCommandAssemblerTest {

    @Test
    void should_createCreateDatasourceCommand_when_toCreateCommand_given_validRequest() {
        JdbcConfigRequest jdbcConfig = new JdbcConfigRequest("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "test-ds", "JDBC", "MYSQL", null, jdbcConfig, null
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertEquals("test-ds", command.name());
        assertEquals(DatasourceType.JDBC, command.type());
        assertEquals(JdbcType.MYSQL, command.subType());
    }

    @Test
    void should_createUpdateDatasourceCommand_when_toUpdateCommand_given_validRequest() {
        UpdateDatasourceRequest request = new UpdateDatasourceRequest(
                1L, "updated-ds", "desc"
        );

        UpdateDatasourceCommand command = DatasourceCommandAssembler.toUpdateCommand(request);

        assertEquals(1L, command.id());
        assertEquals("updated-ds", command.name());
        assertEquals("desc", command.description());
    }

    @Test
    void should_createTestConnectionCommand_when_toTestCommand_given_validRequest() {
        JdbcConfigRequest jdbcConfig = new JdbcConfigRequest("localhost", 3306, "testdb", "root", "pass");
        TestConnectionRequest request = new TestConnectionRequest(
                1L, "test-jdbc", "JDBC", "MYSQL", "test description", jdbcConfig, null
        );

        TestConnectionCommand command = DatasourceCommandAssembler.toTestCommand(request);

        assertEquals(1L, command.id());
        assertEquals("JDBC", command.type());
        assertEquals("MYSQL", command.subType());
        assertEquals("localhost", command.host());
        assertEquals(3306, command.port());
        assertEquals("testdb", command.database());
        assertEquals("root", command.username());
        assertEquals("pass", command.password());
    }

    @Test
    void should_createTestConnectionCommandForApi_when_toTestCommand_given_apiRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user1", "pass1");
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "POST",
                Map.of("Accept", "application/json"), Map.of("key", "val"),
                "{\"test\":1}", "JSON", "$.data", 30, null, authConfig, null, null, null
        );
        TestConnectionRequest request = new TestConnectionRequest(
                2L, "test-api", "API", null, "test api description", null, apiSchema
        );

        TestConnectionCommand command = DatasourceCommandAssembler.toTestCommand(request);

        assertEquals(2L, command.id());
        assertEquals("API", command.type());
        assertEquals("http://api.test.com", command.apiUrl());
        assertEquals("POST", command.apiMethod());
        assertEquals(Map.of("Accept", "application/json"), command.apiHeaders());
        assertEquals(Map.of("key", "val"), command.apiParams());
        assertEquals("{\"test\":1}", command.apiBody());
        assertEquals("JSON", command.apiBodyType());
        assertEquals("BASIC_AUTH", command.apiAuthType());
        assertEquals("user1", command.apiAuthUsername());
        assertEquals(30, command.apiTimeout());
        assertEquals("$.data", command.apiJsonPath());
    }

    @Test
    void should_createListDatasourceQuery_when_toListQuery_given_validRequest() {
        ListDatasourceRequest request = new ListDatasourceRequest("keyword", "API", "ENABLED", 1, 10);

        ListDatasourceQuery query = DatasourceCommandAssembler.toListQuery(request);

        assertEquals("keyword", query.keyword());
        assertEquals(DatasourceType.API, query.type());
        assertEquals(DatasourceStatus.ENABLED, query.status());
        assertEquals(1, query.page());
    }

    @Test
    void should_createListDatasourceQuery_withDefaults_when_toListQuery_given_nullPageAndSize() {
        ListDatasourceRequest request = new ListDatasourceRequest(null, null, null, null, null);

        ListDatasourceQuery query = DatasourceCommandAssembler.toListQuery(request);

        assertEquals(1, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void should_createTableListQuery_when_toTableListQuery_given_validRequest() {
        ListTablesRequest request = new ListTablesRequest(1L, "JDBC", "user", 0, 50);

        TableListQuery query = DatasourceCommandAssembler.toTableListQuery(request);

        assertEquals(1L, query.connectionId());
        assertEquals("user", query.keyword());
    }

    @Test
    void should_returnApiFields_when_toApiFields_given_nullList() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest(null, null, null);
        ParseApiResponseCommand command = DatasourceCommandAssembler.toParseCommand(
                new ParseApiResponseRequest(1L, "http://api.test.com", "GET", null, null, null,
                        null, "$.data", null, null, authConfig, null, null)
        );

        assertEquals("$.data", command.rootPath());
    }

    @Test
    void should_createCreateDatasourceCommandWithApiSchemas_when_toCreateCommand_given_apiRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user", "pass");
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest(
                "PAGE_BASED", "page", "size", "$.total", 20, 100
        );
        ApiFieldRequest field = new ApiFieldRequest("id", "ID", "$.id", "NUMBER", "desc");
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "POST",
                Map.of("Accept", "application/json"), Map.of("key", "val"),
                "{}", "JSON", "$.data", 30, 3,
                authConfig, paginationConfig, null, List.of(field)
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertEquals("api-ds", command.name());
        assertEquals(DatasourceType.API, command.type());
        assertNotNull(command.apiSchemas());
        assertEquals(1, command.apiSchemas().size());
        assertEquals("schema1", command.apiSchemas().get(0).name());
        assertEquals("http://api.test.com", command.apiSchemas().get(0).url());
        assertEquals(HttpMethod.POST, command.apiSchemas().get(0).method());
        assertEquals(ApiAuthType.BASIC_AUTH, command.apiSchemas().get(0).authType());
        assertEquals("user", command.apiSchemas().get(0).authUsername());
        assertEquals("pass", command.apiSchemas().get(0).authPassword());
        assertEquals("PAGE_BASED", command.apiSchemas().get(0).paginationType());
        assertEquals("size", command.apiSchemas().get(0).pageSizeParamName());
        assertEquals("page", command.apiSchemas().get(0).pageNumberParamName());
        assertEquals(20, command.apiSchemas().get(0).pageSize());
        assertEquals(100, command.apiSchemas().get(0).maxPages());
        assertEquals(1, command.apiSchemas().get(0).fields().size());
        assertEquals("id", command.apiSchemas().get(0).fields().get(0).originalName());
    }

    @Test
    void should_createCommandWithNullApiSchemas_when_toCreateCommand_given_nullApiSchemas() {
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "jdbc-ds", "JDBC", "MYSQL", null,
                new JdbcConfigRequest("localhost", 3306, "db", "root", "pass"), null
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertNull(command.apiSchemas());
    }

    @Test
    void should_useNoAuth_when_toApiSchemaCommand_given_nullAuthConfig() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertEquals(ApiAuthType.NO_AUTH, command.apiSchemas().get(0).authType());
        assertNull(command.apiSchemas().get(0).authUsername());
        assertNull(command.apiSchemas().get(0).authPassword());
    }

    @Test
    void should_useNoAuth_when_toApiSchemaCommand_given_unknownAuthType() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("UNKNOWN", null, null);
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                authConfig, null, null, null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertEquals(ApiAuthType.NO_AUTH, command.apiSchemas().get(0).authType());
    }

    @Test
    void should_mapPaginationConfig_when_toApiSchemaCommand_given_paginationConfig() {
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest(
                "PAGE_BASED", "page", "size", "$.total", 20, 100
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, paginationConfig, null, null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertEquals("PAGE_BASED", command.apiSchemas().get(0).paginationType());
        assertEquals("size", command.apiSchemas().get(0).pageSizeParamName());
        assertEquals("page", command.apiSchemas().get(0).pageNumberParamName());
        assertEquals("$.total", command.apiSchemas().get(0).totalCountJsonPath());
        assertEquals(20, command.apiSchemas().get(0).pageSize());
        assertEquals(100, command.apiSchemas().get(0).maxPages());
    }

    @Test
    void should_mapPreOperationConfigs_when_toApiSchemaCommand_given_preOperationConfigs() {
        ParamMappingRequest paramMapping = new ParamMappingRequest("token", "HEADER", "$.access_token");
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                true, "http://auth.example.com/token", "POST",
                Map.of("Content-Type", "application/json"), null,
                "{\"client_id\":\"test\"}", "JSON", List.of(paramMapping)
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );

        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);

        assertNotNull(command.apiSchemas().get(0).preOperationConfigs());
        assertEquals(1, command.apiSchemas().get(0).preOperationConfigs().size());
        assertTrue(command.apiSchemas().get(0).preOperationConfigs().get(0).enabled());
        assertEquals("http://auth.example.com/token", command.apiSchemas().get(0).preOperationConfigs().get(0).url());
        assertEquals(HttpMethod.POST, command.apiSchemas().get(0).preOperationConfigs().get(0).method());
    }

    @Test
    void should_mapParseApiResponseRequest_when_toParseCommand_given_fullRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("basic", "user", "pass");
        ParseApiResponseRequest request = new ParseApiResponseRequest(
                1L, "http://api.test.com", "POST",
                Map.of("Accept", "application/json"), Map.of("key", "val"),
                "{}", "JSON", "$.data", 30, 3, authConfig, null, null
        );

        ParseApiResponseCommand command = DatasourceCommandAssembler.toParseCommand(request);

        assertEquals(1L, command.connectionId());
        assertEquals("http://api.test.com", command.url());
        assertEquals("POST", command.method());
        assertEquals(Map.of("Accept", "application/json"), command.headers());
        assertEquals(Map.of("key", "val"), command.params());
        assertEquals("{}", command.body());
        assertEquals("JSON", command.bodyType());
        assertEquals("basic", command.authType());
        assertEquals("user", command.authUsername());
        assertEquals("pass", command.authPassword());
        assertEquals(30, command.timeout());
        assertEquals(3, command.retryCount());
        assertEquals("$.data", command.rootPath());
    }

    @Test
    void should_mapNullAuthConfig_when_toParseCommand_given_nullAuthConfig() {
        ParseApiResponseRequest request = new ParseApiResponseRequest(
                null, "http://api.test.com", "GET",
                null, null, null, null, "$.data", null, null, null, null, null
        );

        ParseApiResponseCommand command = DatasourceCommandAssembler.toParseCommand(request);

        assertNull(command.authType());
        assertNull(command.authUsername());
        assertNull(command.authPassword());
    }
}

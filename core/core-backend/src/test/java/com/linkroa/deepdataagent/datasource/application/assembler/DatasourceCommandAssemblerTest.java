package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceCommandAssemblerTest {

    private final DatasourceCommandAssembler assembler = Mappers.getMapper(DatasourceCommandAssembler.class);

    @Test
    void should_createCreateDatasourceCommand_when_toCreateCommand_given_validRequest() {
        JdbcConfigRequest jdbcConfig = new JdbcConfigRequest("localhost", 3306, "testdb", "root", "pass", null);
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "test-ds", "JDBC", "MYSQL", null, jdbcConfig, null
        );

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

        assertEquals("test-ds", command.name());
        assertEquals(DatasourceType.JDBC, command.type());
        assertEquals(JdbcType.MYSQL, command.subType());
    }

    @Test
    void should_createUpdateDatasourceCommand_when_toUpdateCommand_given_validRequest() {
        UpdateDatasourceRequest request = new UpdateDatasourceRequest(
                1L, "updated-ds", "desc", null
        );

        UpdateDatasourceCommand command = assembler.toUpdateCommand(request);

        assertEquals(1L, command.id());
        assertEquals("updated-ds", command.name());
        assertEquals("desc", command.description());
        assertNull(command.jdbcConfig());
    }

    @Test
    void should_createTestConnectionCommand_when_toTestCommand_given_validRequest() {
        JdbcConfigRequest jdbcConfig = new JdbcConfigRequest("localhost", 3306, "testdb", "root", "pass", null);
        TestConnectionRequest request = new TestConnectionRequest(
                1L, "test-jdbc", "JDBC", "MYSQL", "test description", jdbcConfig, null
        );

        TestConnectionCommand command = assembler.toTestCommand(request);

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

        TestConnectionCommand command = assembler.toTestCommand(request);

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

        ListDatasourceQuery query = assembler.toListQuery(request);

        assertEquals("keyword", query.keyword());
        assertEquals(DatasourceType.API, query.type());
        assertEquals(DatasourceStatus.ENABLED, query.status());
        assertEquals(1, query.page());
    }

    @Test
    void should_createListDatasourceQuery_withDefaults_when_toListQuery_given_nullPageAndSize() {
        ListDatasourceRequest request = new ListDatasourceRequest(null, null, null, null, null);

        ListDatasourceQuery query = assembler.toListQuery(request);

        assertEquals(1, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void should_createTableListQuery_when_toTableListQuery_given_validRequest() {
        ListTablesRequest request = new ListTablesRequest(1L, "JDBC", "user", 0, 50);

        TableListQuery query = assembler.toTableListQuery(request);

        assertEquals(1L, query.connectionId());
        assertEquals("user", query.keyword());
    }

    @Test
    void should_returnApiFields_when_toApiFields_given_nullList() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest(null, null, null);
        ParseApiResponseCommand command = assembler.toParseCommand(
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

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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
                new JdbcConfigRequest("localhost", 3306, "db", "root", "pass", null), null
        );

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

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

        ParseApiResponseCommand command = assembler.toParseCommand(request);

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

        ParseApiResponseCommand command = assembler.toParseCommand(request);

        assertNull(command.authType());
        assertNull(command.authUsername());
        assertNull(command.authPassword());
    }

    @Test
    void should_returnApiSchemaCommand_when_toApiSchemaCommandFromCreate_given_validRequest() {
        // given
        ApiSchemaRequest schema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );
        CreateApiSchemaRequest request = new CreateApiSchemaRequest(1L, schema);

        // when
        ApiSchemaCommand result = assembler.toApiSchemaCommandFromCreate(request);

        // then
        assertNotNull(result);
        assertEquals("schema1", result.name());
        assertEquals("http://api.test.com", result.url());
        assertEquals(HttpMethod.GET, result.method());
    }

    @Test
    void should_throwException_when_toApiSchemaCommandFromCreate_given_nullSchema() {
        CreateApiSchemaRequest request = new CreateApiSchemaRequest(1L, null);

        assertThrows(IllegalArgumentException.class, () ->
                assembler.toApiSchemaCommandFromCreate(request)
        );
    }

    @Test
    void should_returnNull_when_toApiSchemaCommand_given_nullRequest() {
        ApiSchemaCommand result = assembler.toApiSchemaCommand(null);

        assertNull(result);
    }

    @Test
    void should_preserveBlankBodyType_when_toApiSchemaCommand_given_blankBodyType() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, "  ", null, null, null,
                null, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertEquals("  ", result.bodyType());
    }

    @Test
    void should_preserveNullMethod_when_toApiSchemaCommand_given_nullMethod() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", null,
                null, null, null, "JSON", null, null, null,
                null, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNull(result.method());
    }

    @Test
    void should_useNullAuth_when_toApiSchemaCommand_given_nullAuthConfigInRequest() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertEquals(ApiAuthType.NO_AUTH, result.authType());
        assertNull(result.authUsername());
        assertNull(result.authPassword());
    }

    @Test
    void should_useNullPagination_when_toApiSchemaCommand_given_nullPaginationConfig() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNull(result.paginationType());
        assertNull(result.pageSizeParamName());
        assertNull(result.pageNumberParamName());
        assertNull(result.totalCountJsonPath());
        assertNull(result.pageSize());
        assertNull(result.maxPages());
    }

    @Test
    void should_throwException_when_toApiSchemaCommandFromCreate_given_nullRequest() {
        assertThrows(IllegalArgumentException.class, () ->
                assembler.toApiSchemaCommandFromCreate(null)
        );
    }

    @Test
    void should_returnNull_when_toPreOperationConfig_given_nullRequest() {
        List<PreOperationConfigRequest> preOps = new ArrayList<>();
        preOps.add(null);
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, preOps, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNotNull(result.preOperationConfigs());
        assertEquals(1, result.preOperationConfigs().size());
        assertNull(result.preOperationConfigs().get(0));
    }

    @Test
    void should_mapPreOperationConfigWithNullParamMappings_when_toApiSchemaCommand_given_nullParamMappings() {
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                true, "http://auth.example.com/token", "POST",
                Map.of("Content-Type", "application/json"), null,
                "{\"client_id\":\"test\"}", "JSON", null
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNotNull(result.preOperationConfigs());
        assertEquals(1, result.preOperationConfigs().size());
        assertTrue(result.preOperationConfigs().get(0).enabled());
    }

    @Test
    void should_useDefaultMethod_when_toPreOperationConfig_given_nullMethod() {
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                null, "http://auth.example.com/token", null,
                null, null, null, null, null
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertEquals(HttpMethod.GET, result.preOperationConfigs().get(0).method());
    }

    @Test
    void should_useDefaultEnabled_when_toPreOperationConfig_given_nullEnabled() {
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                null, "http://auth.example.com/token", "POST",
                null, null, null, null, null
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertFalse(result.preOperationConfigs().get(0).enabled());
    }

    @Test
    void should_useNullBodyType_when_toPreOperationConfig_given_nullBodyType() {
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                null, "http://auth.example.com/token", "POST",
                null, null, null, null, null
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNull(result.preOperationConfigs().get(0).bodyType());
    }

    @Test
    void should_useNullBodyType_when_toPreOperationConfig_given_blankBodyType() {
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(
                null, "http://auth.example.com/token", "POST",
                null, null, null, "  ", null
        );
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, List.of(preOp), null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNull(result.preOperationConfigs().get(0).bodyType());
    }

    @Test
    void should_useNoAuth_when_parseAuthType_given_nullAuthType() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest(null, null, null);
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                authConfig, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertEquals(ApiAuthType.NO_AUTH, result.authType());
    }

    @Test
    void should_useNullAuth_when_toTestCommand_given_apiSchemaWithNullAuthConfig() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", "GET",
                null, null, null, null, null, null, null,
                null, null, null, null
        );
        TestConnectionRequest request = new TestConnectionRequest(
                2L, "test-api", "API", null, "test api description", null, apiSchema
        );

        TestConnectionCommand command = assembler.toTestCommand(request);

        assertNull(command.apiAuthType());
        assertNull(command.apiAuthUsername());
        assertNull(command.apiAuthPassword());
    }

    @Test
    void should_useDefaultPage_when_toTableListQuery_given_nullPage() {
        ListTablesRequest request = new ListTablesRequest(1L, "JDBC", "user", null, 50);

        TableListQuery query = assembler.toTableListQuery(request);

        assertEquals(1, query.page());
    }

    @Test
    void should_useDefaultSize_when_toTableListQuery_given_nullSize() {
        ListTablesRequest request = new ListTablesRequest(1L, "JDBC", "user", 0, null);

        TableListQuery query = assembler.toTableListQuery(request);

        assertEquals(50, query.size());
    }

    @Test
    void should_useNullPagination_when_toParseCommand_given_nullPaginationConfig() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("basic", "user", "pass");
        ApiPaginationConfigRequest paginationConfig = null;
        ParseApiResponseRequest request = new ParseApiResponseRequest(
                1L, "http://api.test.com", "POST",
                Map.of("Accept", "application/json"), Map.of("key", "val"),
                "{}", "JSON", "$.data", 30, 3, authConfig, paginationConfig, null
        );

        ParseApiResponseCommand command = assembler.toParseCommand(request);

        assertNull(command.paginationType());
        assertNull(command.pageParamName());
        assertNull(command.sizeParamName());
        assertNull(command.totalCountJsonPath());
        assertNull(command.pageSize());
        assertNull(command.maxPages());
    }

    @Test
    void should_throwException_when_toApiSchemaCommandFromCreate_given_requestWithNullSchema() {
        CreateApiSchemaRequest request = new CreateApiSchemaRequest(1L, null);

        assertThrows(IllegalArgumentException.class, () ->
                assembler.toApiSchemaCommandFromCreate(request)
        );
    }

    @Test
    void should_useNullSubType_when_toCreateCommand_given_jdbcTypeWithNullSubType() {
        JdbcConfigRequest jdbcConfig = new JdbcConfigRequest("localhost", 3306, "testdb", "root", "pass", null);
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "test-ds", "JDBC", null, null, jdbcConfig, null
        );

        CreateDatasourceCommand command = assembler.toCreateCommand(request);

        assertEquals("test-ds", command.name());
        assertEquals(DatasourceType.JDBC, command.type());
        assertNull(command.subType());
    }

    @Test
    void should_useNullMethod_when_toApiSchemaCommand_given_nullMethod() {
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "schema1", "http://api.test.com", null,
                null, null, null, "JSON", null, null, null,
                null, null, null, null
        );

        ApiSchemaCommand result = assembler.toApiSchemaCommand(apiSchema);

        assertNull(result.method());
    }

    @Test
    void should_mapNullFields_when_toTestCommand_given_nullJdbcAndApi() {
        TestConnectionRequest request = new TestConnectionRequest(
                1L, "test", "JDBC", "MYSQL", "desc", null, null
        );

        TestConnectionCommand command = assembler.toTestCommand(request);

        assertNull(command.host());
        assertNull(command.port());
        assertNull(command.database());
        assertNull(command.username());
        assertNull(command.password());
        assertNull(command.apiUrl());
        assertNull(command.apiMethod());
    }
}

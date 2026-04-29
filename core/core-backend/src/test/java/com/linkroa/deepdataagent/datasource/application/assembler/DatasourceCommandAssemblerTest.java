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
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("bearer_token", "token123", null, null);
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest(
                "PAGE_BASED", "size", "page", null, null, null, 20, 100
        );
        ApiConfigRequest apiConfig = new ApiConfigRequest(
                "http://api.test.com", "POST", null, null, null,
                authConfig, paginationConfig, 30, null, null, null, "$.data", null
        );
        UpdateDatasourceRequest request = new UpdateDatasourceRequest(
                1L, "updated-ds", "API", null, "desc", null, apiConfig
        );

        UpdateDatasourceCommand command = DatasourceCommandAssembler.toUpdateCommand(request);

        assertEquals(1L, command.id());
        assertEquals(DatasourceType.API, command.type());
        assertEquals(HttpMethod.POST, command.apiConfig().apiMethod());
    }

    @Test
    void should_createTestConnectionCommand_when_toTestCommand_given_validRequest() {
        // 创建 JDBC 类型的测试连接请求
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
        // 创建 API 类型的测试连接请求
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BEARER_TOKEN", "token123", null, null);
        ApiConfigRequest apiConfig = new ApiConfigRequest(
                "http://api.test.com", "GET", null, null, null,
                authConfig, null, 30, null, null, null, "$.data", null
        );
        TestConnectionRequest request = new TestConnectionRequest(
                2L, "test-api", "API", null, "test api description", null, apiConfig
        );

        TestConnectionCommand command = DatasourceCommandAssembler.toTestCommand(request);

        assertEquals(2L, command.id());
        assertEquals("API", command.type());
        assertEquals("http://api.test.com", command.apiUrl());
        assertEquals("GET", command.apiMethod());
        assertEquals("BEARER_TOKEN", command.apiAuthType());
        assertEquals("token123", command.apiAuthToken());
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
        ParseApiResponseCommand command = DatasourceCommandAssembler.toParseCommand(
                new ParseApiResponseRequest(1L, "http://api.test.com", "/api", "GET", null, null, null, null, null, null, null, null, null, null, "$.data", null)
        );

        assertEquals("$.data", command.rootPath());
    }
}

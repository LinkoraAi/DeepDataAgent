package com.linkroa.deepdataagent.datasource.controller.rest;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.controller.response.*;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceControllerTest {

    @Mock
    private DatasourceApplicationService applicationService;

    @InjectMocks
    private DatasourceController controller;

    @Test
    void should_createDatasource_when_create_given_apiRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BEARER_TOKEN", "token", null, null);
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest("PAGE_BASED", "page", "size", null, null, "$.total", 20, 30);
        ApiConfigRequest apiConfig = new ApiConfigRequest(
                "http://example.com", "POST", Map.of("h", "v"), Map.of("k", "v"), "{}",
                authConfig, paginationConfig, 10, null, null, null, "$.data", null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, apiConfig
        );
        when(applicationService.createDatasource(any(CreateDatasourceCommand.class))).thenReturn(createApiConnection(1L));

        ApiResponse<?> response = controller.create(request);

        assertTrue(response.success());
        verify(applicationService).createDatasource(any(CreateDatasourceCommand.class));
    }

    @Test
    void should_mapTableComment_when_tableList_given_tableInfoResult() {
        TableInfo tableInfo = new TableInfo(1L, 1L, "users", "system comment", null, null, null);
        var result = new DatasourceApplicationService.PaginatedResult<>(List.of(tableInfo), 1L, 0, 50);
        when(applicationService.listTables(any(TableListQuery.class))).thenReturn(result);

        ApiResponse<?> response = controller.tableList(new ListTablesRequest(1L, "JDBC", null, 0, 50));

        assertTrue(response.success());
    }

    @Test
    void should_mapColumnDescription_when_columnList_given_columnInfoResult() {
        ColumnInfo column = new ColumnInfo(1L, 1L, "id", "INTEGER", "column description", null, null, null);
        when(applicationService.listColumns(1L)).thenReturn(List.of(column));

        ApiResponse<List<ColumnInfoResponse>> response = controller.columnList(new ColumnListRequest(1L, 1L, "JDBC"));

        assertTrue(response.success());
        assertEquals("column description", response.data().getFirst().columnComment());
    }

    @Test
    void should_delegateDescriptionUpdate_when_updateColumnComment_given_request() {
        ApiResponse<String> response = controller.updateColumnComment(new UpdateCommentRequest(1L, "biz comment"));

        assertTrue(response.success());
        verify(applicationService).updateColumnComment(1L, "biz comment");
    }

    @Test
    void should_useDefaultPreviewLimit_when_preview_given_nullLimit() {
        when(applicationService.previewTableData(1L, "users", 100)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "users", "JDBC", null));

        assertTrue(response.success());
        verify(applicationService).previewTableData(1L, "users", 100);
    }

    @Test
    void should_returnSuccessMessage_when_testConnection_given_request() {
        when(applicationService.testConnection(any(TestConnectionCommand.class)))
                .thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.ok());

        // 创建 API 类型的测试连接请求
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("NO_AUTH", null, null, null);
        ApiConfigRequest apiConfig = new ApiConfigRequest(
                "http://example.com/api", "GET", null, null, null,
                authConfig, null, 10, null, null, null, "$.data", null
        );
        TestConnectionRequest request = new TestConnectionRequest(
                1L, "test-api", "API", null, "test description", null, apiConfig
        );

        ApiResponse<String> response = controller.testConnection(request);

        assertTrue(response.success());
        assertEquals("连接测试成功", response.data());
    }

    @Test
    void should_returnSuccessMessage_when_sync_given_validId() {
        doNothing().when(applicationService).frontendSyncMetadata(1L);
        
        ApiResponse<String> response = controller.sync(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("同步元数据成功", response.data());
        verify(applicationService).frontendSyncMetadata(1L);
    }

    @Test
    void should_returnPaginatedList_when_list_given_keywordAndStatus() {
        var result = new DatasourceApplicationService.PaginatedResult<>(List.of(createApiConnection(1L)), 1L, 0, 20);
        when(applicationService.listDatasources(any(ListDatasourceQuery.class))).thenReturn(result);

        ApiResponse<PaginatedResponse<DatasourceConnectionResponse>> response = controller.list(
                new ListDatasourceRequest("test", "API", "ENABLED", 0, 20)
        );

        assertTrue(response.success());
        assertEquals(1, response.data().data().size());
    }

    @Test
    void should_updateTableComment_when_updateTableComment_given_request() {
        ApiResponse<String> response = controller.updateTableComment(new UpdateCommentRequest(1L, "table biz comment"));

        assertTrue(response.success());
        verify(applicationService).updateTableComment(1L, "table biz comment");
    }

    @Test
    void should_deleteDatasource_when_delete_given_validId() {
        ApiResponse<String> response = controller.delete(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("删除数据源成功", response.data());
        verify(applicationService).deleteDatasource(1L);
    }

    @Test
    void should_enableDatasource_when_enable_given_validId() {
        ApiResponse<String> response = controller.enable(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("启用数据源成功", response.data());
        verify(applicationService).enableDatasource(1L);
    }

    @Test
    void should_disableDatasource_when_disable_given_validId() {
        ApiResponse<String> response = controller.disable(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("禁用数据源成功", response.data());
        verify(applicationService).disableDatasource(1L);
    }

    @Test
    void should_returnSuccessMessage_when_update_given_validRequest() {
        ApiConfigRequest apiConfig = new ApiConfigRequest(
                "http://example.com/api", "GET", Map.of(), Map.of(), "{}",
                null, null, 10, null, null, null, null, null
        );
        UpdateDatasourceRequest request = new UpdateDatasourceRequest(
                1L, "updated-ds", "API", null, "updated", null, apiConfig
        );

        ApiResponse<String> response = controller.update(request);

        assertTrue(response.success());
        assertEquals("更新数据源成功", response.data());
        verify(applicationService).updateDatasource(any());
    }

    @Test
    void should_returnSupportedTypes_when_getSupportedTypes_given_validRequest() {
        List<DatasourceTypeResponse> expectedTypes = List.of(
                new DatasourceTypeResponse("JDBC", "MYSQL", "MySQL", "OLTP"),
                new DatasourceTypeResponse("JDBC", "CLICKHOUSE", "ClickHouse", "OLAP"),
                new DatasourceTypeResponse("API", "API", "API", "API")
        );
        when(applicationService.getSupportedTypes()).thenReturn(expectedTypes);

        ApiResponse<List<DatasourceTypeResponse>> response = controller.getSupportedTypes();

        assertTrue(response.success());
        assertEquals(3, response.data().size());
        assertEquals("JDBC", response.data().get(0).type());
        assertEquals("MYSQL", response.data().get(0).subType());
        assertEquals("MySQL", response.data().get(0).name());
        assertEquals("OLTP", response.data().get(0).category());
        assertEquals("API", response.data().get(2).type());
        assertEquals("API", response.data().get(2).subType());
        assertEquals("API", response.data().get(2).category());
        verify(applicationService).getSupportedTypes();
    }

    @Test
    void should_returnApiSchemas_when_tableList_given_apiType() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "api_table", "/api", HttpMethod.GET, "$.data", null, null, null, null);
        when(applicationService.listApiSchemas(1L)).thenReturn(List.of(apiSchema));

        ApiResponse<?> response = controller.tableList(new ListTablesRequest(1L, "API", null, 0, 50));

        assertTrue(response.success());
        verify(applicationService).listApiSchemas(1L);
    }

    @Test
    void should_returnApiFields_when_columnList_given_apiType() {
        ApiField apiField = new ApiField(1L, 1L, "field1", "Field1", "$.field1", "STRING", "desc", null, null);
        when(applicationService.listApiFields(1L)).thenReturn(List.of(apiField));

        ApiResponse<List<ColumnInfoResponse>> response = controller.columnList(new ColumnListRequest(1L, 1L, "API"));

        assertTrue(response.success());
        verify(applicationService).listApiFields(1L);
    }

    @Test
    void should_previewApiData_when_previewTable_given_apiType() {
        when(applicationService.previewTableData(1L, "api_table", 100)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "api_table", "API", null));

        assertTrue(response.success());
        verify(applicationService).previewTableData(1L, "api_table", 100);
    }

    @Test
    void should_previewApiDataWithLimit_when_previewTable_given_apiTypeWithLimit() {
        when(applicationService.previewTableData(1L, "api_table", 50)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "api_table", "API", 50));

        assertTrue(response.success());
        verify(applicationService).previewTableData(1L, "api_table", 50);
    }

    @Test
    void should_previewJdbcDataWithLimit_when_previewTable_given_jdbcTypeWithLimit() {
        when(applicationService.previewTableData(1L, "users", 50)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "users", "JDBC", 50));

        assertTrue(response.success());
        verify(applicationService).previewTableData(1L, "users", 50);
    }

    @Test
    void should_parseApiResponse_when_parseResponse_given_validRequest() {
        ParseApiResponseRequest request = new ParseApiResponseRequest(
                null, "http://api.example.com", "/api/test", "GET", null, null, null,
                null, null, null, null, null, 10, null, "$.data", null
        );
        ParseApiResponseResult expectedResult = new ParseApiResponseResult(
                List.of(new ParsedFieldResponse("id", "ID", "NUMBER", "$.id")),
                List.of(Map.of("id", 1))
        );
        when(applicationService.parseApiResponse(any())).thenReturn(expectedResult);

        Object response = controller.parseResponse(request);

        assertNotNull(response);
        verify(applicationService).parseApiResponse(any());
    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null);
    }
}

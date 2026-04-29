package com.linkroa.deepdataagent.datasource.controller.rest;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.controller.response.*;
import com.linkroa.deepdataagent.datasource.domain.model.*;
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
    private DatasourceApplicationService datasourceService;

    @InjectMocks
    private DatasourceController controller;

    @Test
    void should_createDatasource_when_create_given_apiRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("BASIC_AUTH", "user", "pass");
        ApiPaginationConfigRequest paginationConfig = new ApiPaginationConfigRequest("PAGE_BASED", "page", "size", "$.total", 20, 30);
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "users", "http://example.com", "POST", Map.of("h", "v"), Map.of("k", "v"), "{}",
                null, "$.data", 10, null, authConfig, paginationConfig, null, null
        );
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "api-ds", "API", null, "desc", null, List.of(apiSchema)
        );
        when(datasourceService.createDatasource(any(CreateDatasourceCommand.class))).thenReturn(createApiConnection(1L));

        ApiResponse<?> response = controller.create(request);

        assertTrue(response.success());
        verify(datasourceService).createDatasource(any(CreateDatasourceCommand.class));
    }

    @Test
    void should_mapTableComment_when_tableList_given_tableInfoResult() {
        TableInfo tableInfo = new TableInfo(1L, 1L, "users", "system comment", null, null, null);
        var result = new DatasourceApplicationService.PaginatedResult<>(List.of(tableInfo), 1L, 0, 50);
        when(datasourceService.listTables(any(TableListQuery.class))).thenReturn(result);

        ApiResponse<?> response = controller.tableList(new ListTablesRequest(1L, "JDBC", null, 1, 50));

        assertTrue(response.success());
    }

    @Test
    void should_mapColumnDescription_when_columnList_given_columnInfoResult() {
        ColumnInfo column = new ColumnInfo(1L, 1L, "id", "INTEGER", "column description", null, null, null);
        when(datasourceService.listColumns(1L)).thenReturn(List.of(column));

        ApiResponse<List<ColumnInfoResponse>> response = controller.columnList(new ColumnListRequest(1L, 1L, "JDBC", null, null));

        assertTrue(response.success());
        assertEquals("column description", response.data().getFirst().columnComment());
    }

    @Test
    void should_delegateDescriptionUpdate_when_updateColumnComment_given_request() {
        ApiResponse<String> response = controller.updateColumnComment(new UpdateCommentRequest(1L, "biz comment"));

        assertTrue(response.success());
        verify(datasourceService).updateColumnComment(1L, "biz comment");
    }

    @Test
    void should_useDefaultPreviewLimit_when_preview_given_nullLimit() {
        when(datasourceService.previewTableData(1L, "users", 100)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "users", "JDBC", null));

        assertTrue(response.success());
        verify(datasourceService).previewTableData(1L, "users", 100);
    }

    @Test
    void should_returnSuccessMessage_when_testConnection_given_request() {
        when(datasourceService.testConnection(any(TestConnectionCommand.class)))
                .thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.ok());

        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest("NO_AUTH", null, null);
        ApiSchemaRequest apiSchema = new ApiSchemaRequest(
                "test", "http://example.com/api", "GET", null, null, null,
                null, "$.data", 10, null, authConfig, null, null, null
        );
        TestConnectionRequest request = new TestConnectionRequest(
                1L, "test-api", "API", null, "test description", null, apiSchema
        );

        ApiResponse<String> response = controller.testConnection(request);

        assertTrue(response.success());
        assertEquals("连接测试成功", response.data());
    }

    @Test
    void should_returnSuccessMessage_when_sync_given_validId() {
        doNothing().when(datasourceService).syncMetadata(1L);

        ApiResponse<String> response = controller.sync(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("同步元数据成功", response.data());
        verify(datasourceService).syncMetadata(1L);
    }

    @Test
    void should_returnPaginatedList_when_list_given_keywordAndStatus() {
        var result = new DatasourceApplicationService.PaginatedResult<>(List.of(createApiConnection(1L)), 1L, 0, 20);
        when(datasourceService.listDatasources(any(ListDatasourceQuery.class))).thenReturn(result);

        ApiResponse<PaginatedResponse<DatasourceConnectionResponse>> response = controller.list(
                new ListDatasourceRequest("test", "API", "ENABLED", 0, 20)
        );

        assertTrue(response.success());
        assertEquals(1, response.data().list().size());
    }

    @Test
    void should_updateTableComment_when_updateTableComment_given_request() {
        ApiResponse<String> response = controller.updateTableComment(new UpdateCommentRequest(1L, "table biz comment"));

        assertTrue(response.success());
        verify(datasourceService).updateTableComment(1L, "table biz comment");
    }

    @Test
    void should_deleteDatasource_when_delete_given_validId() {
        ApiResponse<String> response = controller.delete(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("删除数据源成功", response.data());
        verify(datasourceService).deleteDatasource(1L);
    }

    @Test
    void should_enableDatasource_when_enable_given_validId() {
        ApiResponse<String> response = controller.enable(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("启用数据源成功", response.data());
        verify(datasourceService).enableDatasource(1L);
    }

    @Test
    void should_disableDatasource_when_disable_given_validId() {
        ApiResponse<String> response = controller.disable(new IdRequest(1L));

        assertTrue(response.success());
        assertEquals("禁用数据源成功", response.data());
        verify(datasourceService).disableDatasource(1L);
    }

    @Test
    void should_returnSuccessMessage_when_update_given_validRequest() {
        UpdateDatasourceRequest request = new UpdateDatasourceRequest(
                1L, "updated-ds", "updated"
        );

        ApiResponse<String> response = controller.update(request);

        assertTrue(response.success());
        assertEquals("更新数据源成功", response.data());
        verify(datasourceService).updateDatasource(any());
    }

    @Test
    void should_returnSupportedTypes_when_getSupportedTypes_given_validRequest() {
        List<DatasourceTypeResponse> expectedTypes = List.of(
                new DatasourceTypeResponse("JDBC", "MYSQL", "MySQL", "OLTP"),
                new DatasourceTypeResponse("JDBC", "CLICKHOUSE", "ClickHouse", "OLAP"),
                new DatasourceTypeResponse("API", "API", "API", "API")
        );
        when(datasourceService.getSupportedTypes()).thenReturn(expectedTypes);

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
        verify(datasourceService).getSupportedTypes();
    }

    @Test
    void should_returnApiSchemas_when_tableList_given_apiType() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "api_table", "http://example.com/api", HttpMethod.GET, null, null, null, null, null);
        when(datasourceService.listApiSchemas(1L)).thenReturn(List.of(apiSchema));

        ApiResponse<PaginatedResponse<TableResponse>> response = controller.tableList(new ListTablesRequest(1L, "API", null, 1, 50));

        assertTrue(response.success());
        assertNotNull(response.data());
        verify(datasourceService).listApiSchemas(1L);
    }

    @Test
    void should_returnApiFields_when_columnList_given_apiType() {
        ApiField apiField = new ApiField(1L, 1L, "field1", "Field1", "$.field1", "STRING", "desc", null, null);
        when(datasourceService.listApiFields(1L)).thenReturn(List.of(apiField));

        ApiResponse<List<ColumnInfoResponse>> response = controller.columnList(new ColumnListRequest(null, 1L, "API", null, null));

        assertTrue(response.success());
        verify(datasourceService).listApiFields(1L);
    }

    @Test
    void should_previewApiData_when_previewTable_given_apiType() {
        when(datasourceService.previewTableData(1L, "api_table", 100)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "api_table", "API", null));

        assertTrue(response.success());
        verify(datasourceService).previewTableData(1L, "api_table", 100);
    }

    @Test
    void should_previewApiDataWithLimit_when_previewTable_given_apiTypeWithLimit() {
        when(datasourceService.previewTableData(1L, "api_table", 50)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "api_table", "API", 50));

        assertTrue(response.success());
        verify(datasourceService).previewTableData(1L, "api_table", 50);
    }

    @Test
    void should_previewJdbcDataWithLimit_when_previewTable_given_jdbcTypeWithLimit() {
        when(datasourceService.previewTableData(1L, "users", 50)).thenReturn(List.of(Map.of("id", 1)));

        ApiResponse<List<Map<String, Object>>> response = controller.previewTable(new PreviewTableRequest(1L, "users", "JDBC", 50));

        assertTrue(response.success());
        verify(datasourceService).previewTableData(1L, "users", 50);
    }

    @Test
    void should_parseApiResponse_when_parseResponse_given_validRequest() {
        ApiAuthConfigRequest authConfig = new ApiAuthConfigRequest(null, null, null);
        ParseApiResponseRequest request = new ParseApiResponseRequest(
                null, "http://api.example.com", "GET", null, null, null, null,
                "$.data", 10, null, authConfig, null, null
        );
        ParseApiResponseResult expectedResult = new ParseApiResponseResult(
                List.of(new ParsedFieldResponse("id", "$.id", "number", List.of())),
                List.of(Map.of("$.id", 1))
        );
        when(datasourceService.parseApiResponse(any(ParseApiResponseCommand.class))).thenReturn(expectedResult);

        ApiResponse<ParseApiResponseResult> response = controller.parseResponse(request);

        assertTrue(response.success());
        assertNotNull(response.data());
        verify(datasourceService).parseApiResponse(any(ParseApiResponseCommand.class));
    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null);
    }
}

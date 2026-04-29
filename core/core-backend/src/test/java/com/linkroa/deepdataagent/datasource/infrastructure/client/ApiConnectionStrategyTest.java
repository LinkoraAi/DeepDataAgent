package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiConnectionStrategyTest {

    @Mock
    private ApiPaginationHandler paginationHandler;

    @Mock
    private ApiSchemaRepository apiSchemaRepository;

    private ApiConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ApiConnectionStrategy(paginationHandler, apiSchemaRepository);
    }

    private DatasourceConnection buildApiConnection() {
        return new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, null, null, null, null, null);
    }

    private ApiSchema buildApiSchema() {
        ApiRequestConfig config = new ApiRequestConfig(null, null, null, null, "$.data", 10,
                null, new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null);
        return new ApiSchema(1L, 1L, "users", "http://example.com/api/users", HttpMethod.GET,
                config, null, null, null, null);
    }

    @Test
    void should_returnSuccessResult_when_testConnection_given_validConnectionWithSchema() {
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(buildApiSchema()));
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), any()))
                .thenReturn(new PaginatedApiResult(List.of(), null, false));

        DatasourceConnection connection = buildApiConnection();

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);

        assertTrue(result.success());
        verify(paginationHandler).executeOnce(any(ApiSchema.class), eq(null), eq(Map.of()));
    }

    @Test
    void should_returnFailResult_when_testConnection_given_noApiSchemaFound() {
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        DatasourceConnection connection = buildApiConnection();

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);

        assertFalse(result.success());
        assertTrue(result.message().contains("未找到API配置"));
    }

    @Test
    void should_returnFailResult_when_testConnection_given_paginationThrows() {
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(buildApiSchema()));
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), any()))
                .thenThrow(new RuntimeException("API unavailable"));

        DatasourceConnection connection = buildApiConnection();

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);

        assertFalse(result.success());
        assertTrue(result.message().contains("API连接失败"));
    }

    @Test
    void should_returnSuccessResult_when_testConnectionWithApiSchema_given_validSchema() {
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), any()))
                .thenReturn(new PaginatedApiResult(List.of(), null, false));

        ApiSchema apiSchema = buildApiSchema();

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(apiSchema);

        assertTrue(result.success());
        verify(paginationHandler).executeOnce(eq(apiSchema), eq(null), eq(Map.of()));
    }

    @Test
    void should_returnDefaultSchema_when_extractSchemas_given_apiConnection() {
        DatasourceConnection connection = buildApiConnection();

        var schemas = strategy.extractSchemas(connection);

        assertEquals(1, schemas.size());
        assertEquals("default", schemas.getFirst().schemaName());
        assertEquals(1L, schemas.getFirst().connectionId());
    }

    @Test
    void should_returnEmptyTables_when_extractTables_given_apiConnection() {
        DatasourceConnection connection = buildApiConnection();

        var tables = strategy.extractTables(connection, "default");

        assertTrue(tables.isEmpty());
    }

    @Test
    void should_returnEmptyColumns_when_extractColumns_given_apiConnection() {
        DatasourceConnection connection = buildApiConnection();

        var columns = strategy.extractColumns(connection, "default", "users");

        assertTrue(columns.isEmpty());
    }

    @Test
    void should_returnDataFromPaginationHandler_when_previewData_given_validConnection() {
        List<Map<String, Object>> expectedData = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
        );
        ApiSchema apiSchema = buildApiSchema();
        PaginatedApiResult paginatedResult = new PaginatedApiResult(expectedData, null, false);

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), eq(Map.of()))).thenReturn(paginatedResult);

        DatasourceConnection connection = buildApiConnection();

        var result = strategy.previewData(connection, null, "users", 100);

        assertEquals(2, result.size());
        verify(apiSchemaRepository).findByConnectionIdAndName(1L, "users");
        verify(paginationHandler).executeOnce(eq(apiSchema), eq(null), eq(Map.of()));
    }

    @Test
    void should_throwRuntimeException_when_previewData_given_paginationHandlerThrows() {
        ApiSchema apiSchema = buildApiSchema();

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), eq(Map.of())))
                .thenThrow(new RuntimeException("Fetch failed"));

        DatasourceConnection connection = buildApiConnection();

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                strategy.previewData(connection, null, "users", 100));

        assertTrue(ex.getMessage().contains("API数据预览失败"));
    }

    @Test
    void should_throwRuntimeException_when_previewData_given_schemaNotFound() {
        when(apiSchemaRepository.findByConnectionIdAndName(1L, "not_exist")).thenReturn(Optional.empty());

        DatasourceConnection connection = buildApiConnection();

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                strategy.previewData(connection, null, "not_exist", 100));

        assertTrue(ex.getMessage().contains("API Schema不存在"));
    }

    @Test
    void should_limitData_when_previewData_given_limitExceedsDataSize() {
        List<Map<String, Object>> expectedData = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob"),
                Map.of("id", 3, "name", "Charlie")
        );
        ApiSchema apiSchema = buildApiSchema();
        PaginatedApiResult paginatedResult = new PaginatedApiResult(expectedData, null, false);

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(ApiSchema.class), any(), eq(Map.of()))).thenReturn(paginatedResult);

        DatasourceConnection connection = buildApiConnection();

        var result = strategy.previewData(connection, null, "users", 2);

        assertEquals(2, result.size());
    }
}

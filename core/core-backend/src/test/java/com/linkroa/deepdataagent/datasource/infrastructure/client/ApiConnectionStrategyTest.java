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
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    void should_returnSuccessResult_when_testConnection_given_validConnection() {
        when(paginationHandler.executeOnce(any(), any(), any()))
                .thenReturn(new PaginatedApiResult(List.of(), null, null, false));

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);

        assertTrue(result.success());
        verify(paginationHandler).executeOnce(eq(connection), eq(null), eq(Map.of()));
    }

    @Test
    void should_returnFailResult_when_testConnection_given_paginationThrows() {
        when(paginationHandler.executeOnce(any(), any(), any()))
                .thenThrow(new RuntimeException("API unavailable"));

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);

        assertFalse(result.success());
        assertTrue(result.message().contains("API连接失败"));
    }

    @Test
    void should_returnDefaultSchema_when_extractSchemas_given_apiConnection() {
        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        var schemas = strategy.extractSchemas(connection);

        assertEquals(1, schemas.size());
        assertEquals("default", schemas.getFirst().schemaName());
        assertEquals(1L, schemas.getFirst().connectionId());
    }

    @Test
    void should_returnEmptyTables_when_extractTables_given_apiConnection() {
        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        var tables = strategy.extractTables(connection, "default");

        assertTrue(tables.isEmpty());
    }

    @Test
    void should_returnEmptyColumns_when_extractColumns_given_apiConnection() {
        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        var columns = strategy.extractColumns(connection, "default", "users");

        assertTrue(columns.isEmpty());
    }

    @Test
    void should_returnDataFromPaginationHandler_when_previewData_given_validConnection() {
        List<Map<String, Object>> expectedData = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
        );
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "users", "/api/users", HttpMethod.GET, "$.data", null, null, null, null);
        PaginatedApiResult paginatedResult = new PaginatedApiResult(expectedData, null, null, false);

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(), any(), eq(Map.of()))).thenReturn(paginatedResult);

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        var result = strategy.previewData(connection, null, "users", 100);

        assertEquals(2, result.size());
        verify(apiSchemaRepository).findByConnectionIdAndName(1L, "users");
        verify(paginationHandler).executeOnce(eq(connection), any(ApiTableConfig.class), eq(Map.of()));
    }

    @Test
    void should_throwRuntimeException_when_previewData_given_paginationHandlerThrows() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "users", "/api/users", HttpMethod.GET, "$.data", null, null, null, null);

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(), any(), eq(Map.of())))
                .thenThrow(new RuntimeException("Fetch failed"));

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                strategy.previewData(connection, null, "users", 100));

        assertTrue(ex.getMessage().contains("API数据预览失败"));
    }

    @Test
    void should_throwRuntimeException_when_previewData_given_schemaNotFound() {
        when(apiSchemaRepository.findByConnectionIdAndName(1L, "not_exist")).thenReturn(Optional.empty());

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

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
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "users", "/api/users", HttpMethod.GET, "$.data", null, null, null, null);
        PaginatedApiResult paginatedResult = new PaginatedApiResult(expectedData, null, null, false);

        when(apiSchemaRepository.findByConnectionIdAndName(1L, "users")).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.executeOnce(any(), any(), eq(Map.of()))).thenReturn(paginatedResult);

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        var result = strategy.previewData(connection, null, "users", 2);

        assertEquals(2, result.size());
    }
}

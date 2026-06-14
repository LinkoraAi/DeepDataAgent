package com.linkroa.deepdataagent.agent.infrastructure.executor;

import com.linkroa.deepdataagent.agent.acl.datasource.ApiConnectionInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiQueryExecutor 单元测试
 * <p>测试 API 查询执行器的 supports 判断和 execute 委托行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiQueryExecutorTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    private ApiQueryExecutor apiQueryExecutor;

    @BeforeEach
    void setUp() {
        apiQueryExecutor = new ApiQueryExecutor(datasourceGateway);
    }

    // ==================== supports ====================

    @Test
    void should_returnTrue_when_supports_given_apiDatasource() {
        // given
        DatasourceInfo apiDatasource = createApiDatasourceInfo(1L, "api-datasource");

        // when
        boolean result = apiQueryExecutor.supports(apiDatasource);

        // then
        assertTrue(result);
    }

    @Test
    void should_returnFalse_when_supports_given_jdbcDatasource() {
        // given
        DatasourceInfo jdbcDatasource = createJdbcDatasourceInfo(1L, "jdbc-datasource");

        // when
        boolean result = apiQueryExecutor.supports(jdbcDatasource);

        // then
        assertFalse(result);
    }

    // ==================== execute ====================

    @Test
    void should_delegateToGateway_when_execute_given_validDatasourceAndQuery() {
        // given
        DatasourceInfo apiDatasource = createApiDatasourceInfo(1L, "api-datasource");
        String apiSchemaName = "user_api";
        List<Map<String, Object>> expectedResults = List.of(
                Map.of("id", 1, "name", "张三"),
                Map.of("id", 2, "name", "李四")
        );
        when(datasourceGateway.executeApiQuery(1L, apiSchemaName, 1000)).thenReturn(expectedResults);

        // when
        List<Map<String, Object>> results = apiQueryExecutor.execute(apiDatasource, apiSchemaName);

        // then
        assertEquals(2, results.size());
        assertEquals("张三", results.get(0).get("name"));
        verify(datasourceGateway).executeApiQuery(1L, apiSchemaName, 1000);
    }

    @Test
    void should_returnEmptyList_when_execute_given_gatewayReturnsEmpty() {
        // given
        DatasourceInfo apiDatasource = createApiDatasourceInfo(2L, "empty-api");
        String apiSchemaName = "empty_api";
        when(datasourceGateway.executeApiQuery(2L, apiSchemaName, 1000)).thenReturn(List.of());

        // when
        List<Map<String, Object>> results = apiQueryExecutor.execute(apiDatasource, apiSchemaName);

        // then
        assertTrue(results.isEmpty());
        verify(datasourceGateway).executeApiQuery(2L, apiSchemaName, 1000);
    }

    @Test
    void should_useDefaultLimit_when_execute_given_executeCalled() {
        // given
        DatasourceInfo apiDatasource = createApiDatasourceInfo(3L, "api-test");
        String apiSchemaName = "order_api";
        when(datasourceGateway.executeApiQuery(3L, apiSchemaName, 1000)).thenReturn(List.of());

        // when
        apiQueryExecutor.execute(apiDatasource, apiSchemaName);

        // then
        verify(datasourceGateway).executeApiQuery(3L, apiSchemaName, 1000);
    }

    @Test
    void should_propagateException_when_execute_given_gatewayThrowsException() {
        // given
        DatasourceInfo apiDatasource = createApiDatasourceInfo(4L, "error-api");
        String apiSchemaName = "error_api";
        when(datasourceGateway.executeApiQuery(4L, apiSchemaName, 1000))
                .thenThrow(new RuntimeException("API 查询失败"));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> apiQueryExecutor.execute(apiDatasource, apiSchemaName));
        assertTrue(exception.getMessage().contains("API 查询失败"));
    }

    // ==================== 辅助方法 ====================

    private DatasourceInfo createApiDatasourceInfo(Long id, String name) {
        return new DatasourceInfo(
                id, name, DatasourceCategory.API, null, true,
                null, new ApiConnectionInfo(id, List.of("schema1", "schema2"))
        );
    }

    private DatasourceInfo createJdbcDatasourceInfo(Long id, String name) {
        return new DatasourceInfo(
                id, name, DatasourceCategory.JDBC, JdbcCategory.MYSQL, true,
                null, null
        );
    }
}

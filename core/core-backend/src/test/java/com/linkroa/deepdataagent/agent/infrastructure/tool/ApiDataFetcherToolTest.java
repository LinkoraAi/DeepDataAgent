package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiDataFetcherTool 单元测试
 * <p>测试 API 数据获取工具的查询委托、空结果处理、数据格式化和异常处理等行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiDataFetcherToolTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    private ObjectMapper objectMapper;

    private ApiDataFetcherTool apiDataFetcherTool;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        apiDataFetcherTool = new ApiDataFetcherTool(datasourceGateway, objectMapper);
    }

    // ==================== executeApiQuery 正常场景 ====================

    @Test
    void should_returnFormattedJson_when_executeApiQuery_given_dataReturned() {
        // given
        Long datasourceId = 1L;
        String apiSchemaName = "user_api";
        Integer limit = 100;
        List<Map<String, Object>> results = List.of(
                Map.of("id", 1, "name", "张三"),
                Map.of("id", 2, "name", "李四")
        );
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, limit)).thenReturn(results);

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertTrue(response.contains("API 返回 2 行数据"));
        assertTrue(response.contains("张三"));
        assertTrue(response.contains("李四"));
        verify(datasourceGateway).executeApiQuery(datasourceId, apiSchemaName, limit);
    }

    @Test
    void should_returnEmptyMessage_when_executeApiQuery_given_emptyResults() {
        // given
        Long datasourceId = 2L;
        String apiSchemaName = "empty_api";
        Integer limit = 1000;
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 500)).thenReturn(List.of());

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertEquals("[EMPTY] API 查询结果为空。", response);
        verify(datasourceGateway).executeApiQuery(datasourceId, apiSchemaName, 500);
    }

    @Test
    void should_useDefaultLimit_when_executeApiQuery_given_nullLimit() {
        // given
        Long datasourceId = 3L;
        String apiSchemaName = "order_api";
        List<Map<String, Object>> results = List.of(
                Map.of("orderId", 100, "amount", 99.9)
        );
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 500)).thenReturn(results);

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, null);

        // then
        assertTrue(response.contains("API 返回 1 行数据"));
        verify(datasourceGateway).executeApiQuery(datasourceId, apiSchemaName, 500);
    }

    @Test
    void should_includeRowCount_when_executeApiQuery_given_multipleRows() {
        // given
        Long datasourceId = 4L;
        String apiSchemaName = "product_api";
        Integer limit = 500;
        List<Map<String, Object>> results = List.of(
                Map.of("id", 1, "product", "商品A"),
                Map.of("id", 2, "product", "商品B"),
                Map.of("id", 3, "product", "商品C")
        );
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 500)).thenReturn(results);

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertTrue(response.contains("API 返回 3 行数据"));
        assertTrue(response.contains("商品A"));
        assertTrue(response.contains("商品B"));
        assertTrue(response.contains("商品C"));
    }

    // ==================== executeApiQuery 异常场景 ====================

    @Test
    void should_returnGenericError_when_executeApiQuery_given_unknownException() {
        // given - 非业务异常：返回通用错误，不暴露底层细节（如 URL/连接信息）
        Long datasourceId = 5L;
        String apiSchemaName = "error_api";
        Integer limit = 100;
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 100))
                .thenThrow(new RuntimeException("Connecting to https://secret.example.com/api failed, token=abc123"));

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertTrue(response.startsWith("[ERROR]"));
        assertTrue(response.contains("API 数据获取失败"));
        assertTrue(response.contains("发生未知错误"));
        assertTrue(!response.contains("secret.example.com"));
        assertTrue(!response.contains("abc123"));
    }

    @Test
    void should_returnReason_when_executeApiQuery_given_schemaNotFound() {
        // given - 业务异常：仅返回干净的业务原因
        Long datasourceId = 6L;
        String apiSchemaName = "nonexistent_api";
        Integer limit = 100;
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 100))
                .thenThrow(new DeepDataAgentException("API Schema 不存在: nonexistent_api"));

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertTrue(response.startsWith("[ERROR]"));
        assertTrue(response.contains("API 数据获取失败"));
        assertTrue(response.contains("API Schema 不存在"));
    }

    @Test
    void should_capLimit_when_executeApiQuery_given_limitExceedsMax() {
        // given - limit 超过单次上限，应被钳制到 500
        Long datasourceId = 7L;
        String apiSchemaName = "big_api";
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 500))
                .thenReturn(List.of(Map.of("id", 1)));

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, 50000);

        // then
        assertTrue(response.startsWith("[DATA]"));
        verify(datasourceGateway).executeApiQuery(datasourceId, apiSchemaName, 500);
    }

    @Test
    void should_appendPaging_when_executeApiQuery_given_resultHitsMax() {
        // given - 返回行数达到单次上限 500，触发截断提示
        Long datasourceId = 8L;
        String apiSchemaName = "max_api";
        java.util.List<Map<String, Object>> bigResult = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            bigResult.add(Map.of("id", i));
        }
        when(datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, 500)).thenReturn(bigResult);

        // when
        String response = apiDataFetcherTool.executeApiQuery(datasourceId, apiSchemaName, 500);

        // then
        assertTrue(response.startsWith("[DATA]"));
        assertTrue(response.contains("[PAGING]"));
    }
}

package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import com.linkroa.deepdataagent.agent.infrastructure.executor.QueryExecutor;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SqlExecutorToolTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private SqlValidationPort sqlValidator;

    private SqlExecutorTool buildTool() {
        DataAnalysisProperties properties = new DataAnalysisProperties();
        return new SqlExecutorTool(datasourceGateway, List.of(queryExecutor),
                mock(tools.jackson.databind.ObjectMapper.class), sqlValidator, properties);
    }

    @Test
    void executeSql_shouldReturnError_whenDatasourceNotFound() {
        when(datasourceGateway.findDatasource(1L)).thenReturn(java.util.Optional.empty());

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertTrue(result.startsWith("[ERROR]"));
        assertTrue(result.contains("数据源不存在"));
    }

    @Test
    void executeSql_shouldReturnError_whenNoExecutorFound() {
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(java.util.Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        when(queryExecutor.supports(any())).thenReturn(false);

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertTrue(result.startsWith("[ERROR]"));
        assertTrue(result.contains("不支持的数据源类型"));
    }

    @Test
    void executeSql_shouldReturnResult_whenSuccess() {
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(java.util.Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        when(queryExecutor.supports(any())).thenReturn(true);
        when(queryExecutor.execute(any(), anyString()))
                .thenReturn(List.of(Map.of("id", 1)));

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertTrue(result.startsWith("[DATA]"));
        assertTrue(result.contains("查询成功，共 1 行"));
    }

    @Test
    void executeSql_shouldReturnEmptyMessage_whenNoResults() {
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(java.util.Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        when(queryExecutor.supports(any())).thenReturn(true);
        when(queryExecutor.execute(any(), anyString())).thenReturn(Collections.emptyList());

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertEquals("[EMPTY] 查询成功，但无数据。", result);
    }

    @Test
    void executeSql_shouldReturnError_whenValidationFails() {
        // given - 二次校验拦截危险 SQL
        doThrow(new DeepDataAgentException("SQL 包含禁止的操作: DROP"))
                .when(sqlValidator).validate(anyString());

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "DROP TABLE users");

        assertTrue(result.startsWith("[ERROR]"));
        assertTrue(result.contains("DROP"));
        verify(sqlValidator).validate("DROP TABLE users");
    }

    @Test
    void executeSql_shouldAppendPaging_whenResultHitsMaxRows() {
        // given - 返回行数达到上限 1000，触发截断提示
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(java.util.Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        when(queryExecutor.supports(any())).thenReturn(true);
        java.util.List<Map<String, Object>> bigResult = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            bigResult.add(Map.of("id", i));
        }
        when(queryExecutor.execute(any(), anyString())).thenReturn(bigResult);

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT id FROM t");

        assertTrue(result.startsWith("[DATA]"));
        assertTrue(result.contains("[PAGING]"));
        assertTrue(result.contains("1000"));
    }

    @Test
    void executeSql_shouldNotAppendPaging_whenResultBelowMaxRows() {
        // given - 返回行数低于上限，不应出现截断提示
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(java.util.Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        when(queryExecutor.supports(any())).thenReturn(true);
        when(queryExecutor.execute(any(), anyString()))
                .thenReturn(List.of(Map.of("id", 1), Map.of("id", 2)));

        SqlExecutorTool sqlExecutorTool = buildTool();
        String result = sqlExecutorTool.executeSql(1L, "SELECT id FROM t");

        assertTrue(result.startsWith("[DATA]"));
        assertTrue(!result.contains("[PAGING]"));
    }
}

package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.infrastructure.executor.QueryExecutor;
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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SqlExecutorToolTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private QueryExecutor queryExecutor;

    @Test
    void executeSql_shouldReturnError_whenDatasourceNotFound() {
        when(datasourceGateway.findDatasource(1L)).thenReturn(java.util.Optional.empty());

        SqlExecutorTool sqlExecutorTool = new SqlExecutorTool(datasourceGateway, List.of(queryExecutor), mock(tools.jackson.databind.ObjectMapper.class));
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

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

        SqlExecutorTool sqlExecutorTool = new SqlExecutorTool(datasourceGateway, List.of(queryExecutor), mock(tools.jackson.databind.ObjectMapper.class));
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

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

        SqlExecutorTool sqlExecutorTool = new SqlExecutorTool(datasourceGateway, List.of(queryExecutor), mock(tools.jackson.databind.ObjectMapper.class));
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertTrue(result.contains("查询返回 1 行数据"));
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

        SqlExecutorTool sqlExecutorTool = new SqlExecutorTool(datasourceGateway, List.of(queryExecutor), mock(tools.jackson.databind.ObjectMapper.class));
        String result = sqlExecutorTool.executeSql(1L, "SELECT 1");

        assertEquals("查询结果为空。可能原因：1) 查询条件过于严格 2) 数据表中确实没有匹配数据", result);
    }
}

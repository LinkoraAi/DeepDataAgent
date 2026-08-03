package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.domain.service.TextToSqlService;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TextToSqlTool 单元测试
 * <p>测试 Text-to-SQL 工具的核心逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TextToSqlToolTest {

    @Mock
    private TextToSqlService textToSqlService;

    @Mock
    private DatasourceGateway datasourceGateway;

    @Mock
    private ToolCallParam toolCallParam;

    @Mock
    private SessionToolContext sessionToolContext;

    private TextToSqlTool textToSqlTool;

    @BeforeEach
    void setUp() {
        textToSqlTool = new TextToSqlTool(textToSqlService, datasourceGateway, sessionToolContext);
    }

    @Test
    void generateSql_shouldReturnError_whenSessionIdIsBlank() {
        String result = textToSqlTool.generateSql(
                1L, "question", " ", toolCallParam
        );

        assertTrue(result.contains("sessionId is required"));
    }

    @Test
    void generateSql_shouldReturnError_whenModelConfigIdMissing() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        // 使用 doReturn 避免泛型方法 stub 问题
        doReturn(null).when(ctx).get("model_config_id");

        // 显式 stub sessionToolContext.getModelConfigId 返回 null，确保从 RuntimeContext 获取
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);

        String result = textToSqlTool.generateSql(
                1L, "question", "session-1", toolCallParam
        );

        assertTrue(result.contains("modelConfigId not available"));
    }

    @Test
    void generateSql_shouldReturnSql_whenSuccess() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(datasourceGateway.extractSchema(1L)).thenReturn("CREATE TABLE t (id INT)");
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.MYSQL,
                        true, null, null
                )));
        doReturn("SELECT * FROM t").when(textToSqlService).convert(anyLong(), anyString(), anyString(), anyString(), anyString());

        String result = textToSqlTool.generateSql(
                1L, "question", "session-1", toolCallParam
        );

        assertEquals("SELECT * FROM t", result);
    }

    @Test
    void generateSql_shouldReturnError_whenSessionIdIsNull() {
        String result = textToSqlTool.generateSql(
                1L, "question", null, toolCallParam
        );

        assertTrue(result.contains("sessionId is required"));
    }

    @Test
    void generateSql_shouldReturnClickHouseDialect_whenDatasourceIsClickHouse() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(datasourceGateway.extractSchema(1L)).thenReturn("CREATE TABLE t (id INT)");
        when(datasourceGateway.findDatasource(1L))
                .thenReturn(Optional.of(new DatasourceInfo(
                        1L, "test", DatasourceCategory.JDBC, JdbcCategory.CLICKHOUSE,
                        true, null, null
                )));
        doReturn("SELECT * FROM t").when(textToSqlService).convert(anyLong(), anyString(), anyString(), anyString(), anyString());

        String result = textToSqlTool.generateSql(
                1L, "question", "session-1", toolCallParam
        );

        assertEquals("SELECT * FROM t", result);
    }

    @Test
    void generateSql_shouldReturnError_whenServiceThrowsException() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(datasourceGateway.extractSchema(1L)).thenThrow(new RuntimeException("DB connection failed"));

        String result = textToSqlTool.generateSql(
                1L, "question", "session-1", toolCallParam
        );

        assertTrue(result.contains("Failed to generate SQL"));
    }
}
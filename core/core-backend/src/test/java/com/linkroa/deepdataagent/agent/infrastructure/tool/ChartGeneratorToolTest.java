package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChartGeneratorToolTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private DataSummaryBuilder dataSummaryBuilder;

    @Mock
    private ToolCallParam toolCallParam;

    @Mock
    private SessionToolContext sessionToolContext;

    @Test
    void generateChart_shouldReturnEmpty_whenSessionIdIsBlank() {
        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);

        String result = tool.generateChart(
                "queryResult", "question", " ", toolCallParam
        );

        assertEquals("[ERROR] 图表生成失败: sessionId 参数为空", result);
    }

    @Test
    void generateChart_shouldReturnEmpty_whenModelConfigIdMissing() {
        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(sessionToolContext.getModelConfigId("session-1")).thenReturn(null);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(ctx.get("model_config_id")).thenReturn(null);

        String result = tool.generateChart(
                "queryResult", "question", "session-1", toolCallParam
        );

        assertEquals("[ERROR] 图表生成失败: 未找到可用的模型配置", result);
        verify(llmClient, never()).generateChartConfig(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void generateChart_shouldReturnChart_whenSuccess() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(dataSummaryBuilder.build(any()))
                .thenReturn("data description");
        // 使用 any() matchers 避免 LENIENT 模式下参数匹配问题
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);
        doReturn("{\"chart\": true}").when(llmClient).generateChartConfig(anyLong(), anyString(), anyString(), anyString());

        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateChart(
                "[{\"a\":1}]", "question", "session-1", toolCallParam
        );

        assertEquals("{\"chart\": true}", result);
    }

    @Test
    void generateChart_shouldParseModelConfigId_whenStoredAsInteger() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1); // Integer 而非 Long
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(dataSummaryBuilder.build(any()))
                .thenReturn("data description");
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);
        doReturn("{\"chart\": true}").when(llmClient).generateChartConfig(anyLong(), anyString(), anyString(), anyString());

        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateChart(
                "[{\"a\":1}]", "question", "session-1", toolCallParam
        );

        assertEquals("{\"chart\": true}", result);
    }

    @Test
    void generateChart_shouldReturnEmpty_whenLLMClientFails() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(dataSummaryBuilder.build(any()))
                .thenReturn("data description");
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);
        doReturn("{\"chart\": true}").when(llmClient).generateChartConfig(anyLong(), anyString(), anyString(), anyString());
        when(llmClient.generateChartConfig(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM failed"));

        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateChart(
                "[{\"a\":1}]", "question", "session-1", toolCallParam
        );

        assertEquals("[ERROR] 图表生成失败: LLM failed", result);
    }

    @Test
    void generateChart_shouldReturnEmpty_whenDataIsEmptyJsonArray() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);
        // When data is an empty JSON array, buildDataDescription returns "[]"
        // and llmClient.generateChartConfig is called with "[]" as dataDescription
        doReturn("{}").when(llmClient).generateChartConfig(anyLong(), anyString(), anyString(), anyString());

        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateChart(
                "[]", "question", "session-1", toolCallParam
        );

        assertEquals("{}", result);
    }

    @Test
    void generateChart_shouldStripDataPrefix_whenQueryResultHasPrefix() {
        // given - queryResult 带有 execute_sql 的 [DATA] 前缀，应剥离后传给 build，再由 build 结果传给 LLM
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);
        // build 返回固定摘要；若前缀未被剥离，readValue 会走 catch 分支返回原始串，build 不会被调用
        when(dataSummaryBuilder.build(any())).thenReturn("PARSED_SUMMARY");
        when(llmClient.generateChartConfig(1L, "PARSED_SUMMARY", "question", "session-1"))
                .thenReturn("{\"chart\": true}");

        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateChart(
                "[DATA] 查询成功，共 1 行：\n[{\"a\":1}]", "question", "session-1", toolCallParam
        );

        assertEquals("{\"chart\": true}", result);
    }
}

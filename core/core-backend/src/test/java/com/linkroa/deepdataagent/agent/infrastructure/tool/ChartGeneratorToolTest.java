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

        assertEquals("{}", result);
    }

    @Test
    void generateChart_shouldReturnEmpty_whenModelConfigIdMissing() {
        ChartGeneratorTool tool = new ChartGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);

        String result = tool.generateChart(
                "queryResult", "question", "session-1", toolCallParam
        );

        assertEquals("{}", result);
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

        assertEquals("{}", result);
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
}

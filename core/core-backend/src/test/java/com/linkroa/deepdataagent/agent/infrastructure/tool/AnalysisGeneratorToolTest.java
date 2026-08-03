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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AnalysisGeneratorTool 单元测试
 * <p>测试分析报告生成工具的核心逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisGeneratorToolTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private DataSummaryBuilder dataSummaryBuilder;

    @Mock
    private ToolCallParam toolCallParam;

    @Mock
    private SessionToolContext sessionToolContext;

    @Test
    void generateAnalysis_shouldReturnError_whenSessionIdIsBlank() {
        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);

        String result = tool.generateAnalysis(
                "question", "sql", "{}", "chart", " ", toolCallParam
        );

        assertTrue(result.contains("sessionId is required"));
    }

    @Test
    void generateAnalysis_shouldReturnError_whenModelConfigIdMissing() {
        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        // Explicitly stub sessionToolContext to return null (ensuring modelConfigId is not available)
        when(sessionToolContext.getModelConfigId(anyString())).thenReturn(null);

        String result = tool.generateAnalysis(
                "question", "sql", "{}", "chart", "session-1", toolCallParam
        );

        assertTrue(result.contains("modelConfigId not available"));
    }

    @Test
    void generateAnalysis_shouldReturnAnalysis_whenSuccess() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        lenient().when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        lenient().when(dataSummaryBuilder.build(any()))
                .thenReturn("data summary");
        doReturn("analysis report").when(llmClient).generateAnalysis(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateAnalysis(
                "question", "sql", "[{\"a\":1}]", "chart", "session-1", toolCallParam
        );

        assertEquals("analysis report", result);
    }

    @Test
    void generateAnalysis_shouldThrowException_whenLLMClientFails() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        lenient().when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        lenient().when(dataSummaryBuilder.build(any()))
                .thenReturn("data summary");
        doReturn("analysis report").when(llmClient).generateAnalysis(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        // Override for the exception test case
        when(llmClient.generateAnalysis(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM failed"));

        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                tool.generateAnalysis("question", "sql", "[{\"a\":1}]", "chart", "session-1", toolCallParam)
        );
        assertTrue(ex.getMessage().contains("分析报告生成失败"));
    }

    @Test
    void generateAnalysis_shouldHandleInvalidJsonInput_whenJsonIsNotValidArray() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        lenient().when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        // Invalid JSON input - not a valid JSON array and doesn't contain brackets
        // The buildDataSummaryFromJson will fall through to return the raw text
        String invalidJson = "plain text without json array";
        lenient().doReturn("analysis report").when(llmClient).generateAnalysis(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateAnalysis(
                "question", "sql", invalidJson, "chart", "session-1", toolCallParam
        );

        assertEquals("analysis report", result);
    }

    @Test
    void generateAnalysis_shouldHandleTextWithEmbeddedJsonArray() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        lenient().when(ctx.get("model_config_id")).thenReturn(1L);
        when(toolCallParam.getRuntimeContext()).thenReturn(ctx);
        // Text with embedded JSON array
        String textWithJson = "查询返回 2 行数据：\n[{\"a\":1},{\"b\":2}]";
        lenient().when(dataSummaryBuilder.build(any()))
                .thenReturn("extracted summary");
        lenient().doReturn("analysis report").when(llmClient).generateAnalysis(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

        AnalysisGeneratorTool tool = new AnalysisGeneratorTool(llmClient, dataSummaryBuilder, new tools.jackson.databind.ObjectMapper(), sessionToolContext);
        String result = tool.generateAnalysis(
                "question", "sql", textWithJson, "chart", "session-1", toolCallParam
        );

        assertEquals("analysis report", result);
    }
}
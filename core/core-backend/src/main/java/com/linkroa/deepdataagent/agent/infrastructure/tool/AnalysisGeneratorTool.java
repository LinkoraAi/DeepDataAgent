package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 分析报告生成工具
 * <p>根据查询结果、SQL、数据摘要和图表生成完整分析报告，供 Agent 调用。</p>
 */
@Component
public class AnalysisGeneratorTool {

    private static final Logger log = LoggerFactory.getLogger(AnalysisGeneratorTool.class);
    private static final TypeReference<List<Map<String, Object>>> DATA_TYPE = new TypeReference<>() {};

    /** RuntimeContext 中存储 modelConfigId 的键名 */
    private static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    private final LLMClient llmClient;
    private final DataSummaryBuilder dataSummaryBuilder;
    private final ObjectMapper objectMapper;
    private final SessionToolContext sessionToolContext;

    public AnalysisGeneratorTool(LLMClient llmClient, DataSummaryBuilder dataSummaryBuilder, ObjectMapper objectMapper, SessionToolContext sessionToolContext) {
        this.llmClient = llmClient;
        this.dataSummaryBuilder = dataSummaryBuilder;
        this.objectMapper = objectMapper;
        this.sessionToolContext = sessionToolContext;
    }

    @Tool(name = "generate_analysis",
          description = "Generate a complete data analysis report based on the user's question, " +
                        "query context (SQL statement or API data source descriptor), query result data (JSON), " +
                        "and chart information (if available). " +
                        "Call this LAST to produce the final report. " +
                        "Returns the report in structured Markdown format.")
    public String generateAnalysis(
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's original question") String userQuestion,
            @ToolParam(name = "sqlQuery", required = true,
                       description = "The executed query statement (SQL for JDBC datasource, or 'API: schemaName' for API datasource)") String sqlQuery,
            @ToolParam(name = "queryResultJson", required = true,
                       description = "The query result data in JSON format (array of objects)") String queryResultJson,
            @ToolParam(name = "chartSummary", required = true,
                       description = "The chart description or configuration") String chartSummary,
            @ToolParam(name = "sessionId", required = true,
                       description = "The session ID from the user message (format: UUID)") String sessionId,
            ToolCallParam toolCallParam
    ) {
        log.info("AnalysisGeneratorTool: generating analysis report for question='{}', sessionId={}", userQuestion, sessionId);
        try {
            if (sessionId == null || sessionId.isBlank()) {
                log.error("AnalysisGeneratorTool: sessionId parameter is empty");
                return "分析报告生成失败：sessionId is required";
            }
            
            // 从 SessionToolContext 获取 modelConfigId
            Long modelConfigId = sessionToolContext.getModelConfigId(sessionId);
            if (modelConfigId == null) {
                // 兜底：尝试从 RuntimeContext 获取
                if (toolCallParam != null && toolCallParam.getRuntimeContext() != null) {
                    modelConfigId = (Long) toolCallParam.getRuntimeContext().get(CTX_KEY_MODEL_CONFIG_ID);
                }
            }
            if (modelConfigId == null) {
                log.error("AnalysisGeneratorTool: modelConfigId not available for sessionId={}", sessionId);
                return "分析报告生成失败：modelConfigId not available in context";
            }
            
            String dataSummary = buildDataSummaryFromJson(queryResultJson);
            String analysis = llmClient.generateAnalysis(modelConfigId, userQuestion, sqlQuery, dataSummary, chartSummary, sessionId);
            log.info("AnalysisGeneratorTool: analysis report generated, length={}", analysis.length());
            return analysis;
        } catch (Exception e) {
            log.error("AnalysisGeneratorTool: failed to generate analysis report", e);
            throw new RuntimeException("分析报告生成失败", e);
        }
    }

    private static final int MAX_FALLBACK_DATA_LENGTH = 5000;

    private String buildDataSummaryFromJson(String queryResultJson) {
        try {
            // 尝试直接解析
            List<Map<String, Object>> data = objectMapper.readValue(queryResultJson, DATA_TYPE);
            return dataSummaryBuilder.build(data);
        } catch (Exception e) {
            log.debug("AnalysisGeneratorTool: direct JSON parse failed, attempting extraction");
        }

        // 尝试从带前缀的字符串中提取 JSON 数组（如 SqlExecutorTool 返回的 "查询返回 N 行数据：\n[...]"）
        String extracted = extractJsonArray(queryResultJson);
        if (extracted != null) {
            try {
                List<Map<String, Object>> data = objectMapper.readValue(extracted, DATA_TYPE);
                return dataSummaryBuilder.build(data);
            } catch (Exception e) {
                log.warn("AnalysisGeneratorTool: failed to parse extracted JSON array, using truncated raw data as summary", e);
            }
        }

        // 最终降级：使用截断的原始文本
        if (queryResultJson.length() > MAX_FALLBACK_DATA_LENGTH) {
            return queryResultJson.substring(0, MAX_FALLBACK_DATA_LENGTH) + "\n...(数据截断)";
        }
        return queryResultJson;
    }

    /**
     * 从可能包含前缀文本的字符串中提取 JSON 数组部分
     * <p>LLM 可能将 SqlExecutorTool 的完整输出（含 "查询返回 N 行数据：\n" 前缀）
     * 直接传递给 generate_analysis 工具，导致 JSON 解析失败。
     * 此方法尝试定位第一个 '[' 和最后一个 ']' 来提取纯 JSON 数组。</p>
     *
     * @param input 可能包含前缀的字符串
     * @return 提取的 JSON 数组字符串，如果未找到则返回 null
     */
    private String extractJsonArray(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        int start = input.indexOf('[');
        int end = input.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return input.substring(start, end + 1);
        }
        return null;
    }
}

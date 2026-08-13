package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.exception.AnalysisCancelledException;
import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import com.linkroa.deepdataagent.agent.infrastructure.client.ChartConfigGenerationClient;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
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
 * 图表生成工具
 * <p>根据查询结果生成 ECharts 图表配置，供 Agent 调用。</p>
 */
@Component
public class ChartGeneratorTool {

    private static final Logger log = LoggerFactory.getLogger(ChartGeneratorTool.class);
    private static final TypeReference<List<Map<String, Object>>> DATA_TYPE = new TypeReference<>() {};

    /** RuntimeContext 中存储 modelConfigId 的键名 */
    private static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    private final ChartConfigGenerationClient chartConfigGenerationClient;
    private final DataSummaryBuilder dataSummaryBuilder;
    private final ObjectMapper objectMapper;
    private final SessionToolContext sessionToolContext;

    public ChartGeneratorTool(ChartConfigGenerationClient chartConfigGenerationClient, DataSummaryBuilder dataSummaryBuilder, ObjectMapper objectMapper, SessionToolContext sessionToolContext) {
        this.chartConfigGenerationClient = chartConfigGenerationClient;
        this.dataSummaryBuilder = dataSummaryBuilder;
        this.objectMapper = objectMapper;
        this.sessionToolContext = sessionToolContext;
    }

    @Tool(name = "generate_chart",
          description = "Conditionally generate an ECharts chart configuration JSON based on query results. " +
                        "Only call this AFTER execute_sql or execute_api_query when the data is suitable for visualization " +
                        "and the user's question indicates a chart is expected (e.g., contains keywords like 'chart', 'trend', 'visualization'). " +
                        "Skip if data is a single metric, pure text, or already clear in table form. " +
                        "Returns the chart configuration.")
    public String generateChart(
            @ToolParam(name = "queryResult", required = true,
                       description = "The query result data in JSON format") String queryResult,
            @ToolParam(name = "text", required = true,
                       description = "The user's original question for context") String text,
            @ToolParam(name = "sessionId", required = true,
                       description = "The session ID from the user message (format: UUID)") String sessionId,
            ToolCallParam toolCallParam
    ) {
        log.info("ChartGeneratorTool: generating chart for question='{}', sessionId={}", text, sessionId);
        try {
            if (sessionId == null || sessionId.isBlank()) {
                log.error("ChartGeneratorTool: sessionId parameter is empty");
                return AgentToolResponse.error("图表生成失败: sessionId 参数为空");
            }
            
            // 从 SessionToolContext 获取 modelConfigId
            Long modelConfigId = sessionToolContext.getModelConfigId(sessionId);
            if (modelConfigId == null) {
                // 兜底：尝试从 RuntimeContext 获取
                if (toolCallParam != null && toolCallParam.getRuntimeContext() != null) {
                    Object configId = toolCallParam.getRuntimeContext().get(CTX_KEY_MODEL_CONFIG_ID);
                    if (configId instanceof Number number) {
                        modelConfigId = number.longValue();
                    }
                }
            }
            if (modelConfigId == null) {
                log.error("ChartGeneratorTool: modelConfigId not available for sessionId={}", sessionId);
                return AgentToolResponse.error("图表生成失败: 未找到可用的模型配置");
            }
            
            String dataDescription = buildDataDescription(queryResult);
            return chartConfigGenerationClient.generateChartConfig(modelConfigId, dataDescription, text, sessionId);

        } catch (AnalysisCancelledException e) {
            log.info("ChartGeneratorTool: chart generation cancelled, sessionId={}", sessionId);
            String reason = e.getMessage() != null ? e.getMessage() : "未知错误";
            return AgentToolResponse.error("图表生成失败: " + reason);
        } catch (Exception e) {
            log.error("ChartGeneratorTool: failed to generate chart", e);
            String reason = e.getMessage() != null ? e.getMessage() : "未知错误";
            return AgentToolResponse.error("图表生成失败: " + reason);
        }
    }

    private String buildDataDescription(String queryResult) {
        try {
            // 剥离 execute_sql / execute_api_query 返回的前缀及行数说明，仅保留 JSON 部分
            String json = AgentToolResponse.stripPrefix(queryResult);
            List<Map<String, Object>> data = objectMapper.readValue(json, DATA_TYPE);
            if (data.isEmpty()) {
                return "[]";
            }
            return dataSummaryBuilder.build(data);
        } catch (Exception e) {
            log.warn("ChartGeneratorTool: failed to parse query result JSON, using raw data", e);
            return queryResult;
        }
    }
}

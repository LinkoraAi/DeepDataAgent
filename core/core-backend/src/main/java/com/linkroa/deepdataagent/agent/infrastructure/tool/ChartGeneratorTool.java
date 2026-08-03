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
 * 图表生成工具
 * <p>根据查询结果生成 ECharts 图表配置，供 Agent 调用。</p>
 */
@Component
public class ChartGeneratorTool {

    private static final Logger log = LoggerFactory.getLogger(ChartGeneratorTool.class);
    private static final TypeReference<List<Map<String, Object>>> DATA_TYPE = new TypeReference<>() {};

    /** RuntimeContext 中存储 modelConfigId 的键名 */
    private static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    private final LLMClient llmClient;
    private final DataSummaryBuilder dataSummaryBuilder;
    private final ObjectMapper objectMapper;
    private final SessionToolContext sessionToolContext;

    public ChartGeneratorTool(LLMClient llmClient, DataSummaryBuilder dataSummaryBuilder, ObjectMapper objectMapper, SessionToolContext sessionToolContext) {
        this.llmClient = llmClient;
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
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's original question for context") String userQuestion,
            @ToolParam(name = "sessionId", required = true,
                       description = "The session ID from the user message (format: UUID)") String sessionId,
            ToolCallParam toolCallParam
    ) {
        log.info("ChartGeneratorTool: generating chart for question='{}', sessionId={}", userQuestion, sessionId);
        try {
            if (sessionId == null || sessionId.isBlank()) {
                log.error("ChartGeneratorTool: sessionId parameter is empty");
                return "{}";
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
                log.error("ChartGeneratorTool: modelConfigId not available for sessionId={}", sessionId);
                return "{}";
            }
            
            String dataDescription = buildDataDescription(queryResult);
            String echartsJson = llmClient.generateChartConfig(modelConfigId, dataDescription, userQuestion, sessionId);
            log.info("ChartGeneratorTool: chart generated, length={}", echartsJson.length());
            return echartsJson;
        } catch (Exception e) {
            log.error("ChartGeneratorTool: failed to generate chart", e);
            return "{}";
        }
    }

    private String buildDataDescription(String queryResult) {
        try {
            List<Map<String, Object>> data = objectMapper.readValue(queryResult, DATA_TYPE);
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

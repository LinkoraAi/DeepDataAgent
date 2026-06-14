package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import io.agentscope.core.tool.Tool;
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

    private final LLMClient llmClient;
    private final DataSummaryBuilder dataSummaryBuilder;
    private final ObjectMapper objectMapper;

    public ChartGeneratorTool(LLMClient llmClient, DataSummaryBuilder dataSummaryBuilder, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.dataSummaryBuilder = dataSummaryBuilder;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "generate_chart",
          description = "Generate an ECharts chart configuration JSON based on query results. " +
                        "Call this AFTER execute_sql or execute_api_query to visualize the data. " +
                        "Returns the chart configuration.")
    public String generateChart(
            @ToolParam(name = "modelConfigId", required = true,
                       description = "The LLM model configuration ID") Long modelConfigId,
            @ToolParam(name = "queryResult", required = true,
                       description = "The query result data in JSON format") String queryResult,
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's original question for context") String userQuestion
    ) {
        log.info("ChartGeneratorTool: generating chart for question='{}'", userQuestion);
        try {
            String dataDescription = buildDataDescription(queryResult);
            String echartsJson = llmClient.generateChartConfig(modelConfigId, dataDescription, userQuestion);
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

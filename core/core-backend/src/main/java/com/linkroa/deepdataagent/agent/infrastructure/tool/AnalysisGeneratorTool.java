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
 * 分析报告生成工具
 * <p>根据查询结果、SQL、数据摘要和图表生成完整分析报告，供 Agent 调用。</p>
 */
@Component
public class AnalysisGeneratorTool {

    private static final Logger log = LoggerFactory.getLogger(AnalysisGeneratorTool.class);
    private static final TypeReference<List<Map<String, Object>>> DATA_TYPE = new TypeReference<>() {};

    private final LLMClient llmClient;
    private final DataSummaryBuilder dataSummaryBuilder;
    private final ObjectMapper objectMapper;

    public AnalysisGeneratorTool(LLMClient llmClient, DataSummaryBuilder dataSummaryBuilder, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.dataSummaryBuilder = dataSummaryBuilder;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "generate_analysis",
          description = "Generate a complete data analysis report based on the user's question, " +
                        "query context (SQL statement or API data source descriptor), query result data (JSON), " +
                        "and chart information. " +
                        "Call this LAST after generate_chart to produce the final report. " +
                        "Returns the report in structured Markdown format.")
    public String generateAnalysis(
            @ToolParam(name = "modelConfigId", required = true,
                       description = "The LLM model configuration ID") Long modelConfigId,
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's original question") String userQuestion,
            @ToolParam(name = "sqlQuery", required = true,
                       description = "The executed query statement (SQL for JDBC datasource, or 'API: schemaName' for API datasource)") String sqlQuery,
            @ToolParam(name = "queryResultJson", required = true,
                       description = "The query result data in JSON format (array of objects)") String queryResultJson,
            @ToolParam(name = "chartSummary", required = true,
                       description = "The chart description or configuration") String chartSummary
    ) {
        log.info("AnalysisGeneratorTool: generating analysis report for question='{}'", userQuestion);
        try {
            String dataSummary = buildDataSummaryFromJson(queryResultJson);
            String analysis = llmClient.generateAnalysis(modelConfigId, userQuestion, sqlQuery, dataSummary, chartSummary);
            log.info("AnalysisGeneratorTool: analysis report generated, length={}", analysis.length());
            return analysis;
        } catch (Exception e) {
            log.error("AnalysisGeneratorTool: failed to generate analysis report", e);
            return "分析报告生成失败";
        }
    }

    private static final int MAX_FALLBACK_DATA_LENGTH = 5000;

    private String buildDataSummaryFromJson(String queryResultJson) {
        try {
            List<Map<String, Object>> data = objectMapper.readValue(queryResultJson, DATA_TYPE);
            return dataSummaryBuilder.build(data);
        } catch (Exception e) {
            log.warn("AnalysisGeneratorTool: failed to parse query result JSON, using truncated raw data as summary", e);
            if (queryResultJson.length() > MAX_FALLBACK_DATA_LENGTH) {
                return queryResultJson.substring(0, MAX_FALLBACK_DATA_LENGTH) + "\n...(数据截断)";
            }
            return queryResultJson;
        }
    }
}
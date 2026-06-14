package com.linkroa.deepdataagent.agent.application.event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.linkroa.deepdataagent.agent.controller.response.DataAnalysisResponse;

/**
 * SSE 事件模型，用于流式推送分析过程。
 */
public record AnalysisEvent(
    String type,
    String message,
    String payload
) {
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_SQL = "sql";
    public static final String TYPE_DATA = "data";
    public static final String TYPE_CHART = "chart";
    public static final String TYPE_ANALYSIS = "analysis";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static AnalysisEvent thinking(String message) {
        return new AnalysisEvent(TYPE_THINKING, message, null);
    }

    public static AnalysisEvent toolCall(String toolName) {
        return new AnalysisEvent(TYPE_TOOL_CALL, toolName, null);
    }

    public static AnalysisEvent toolResult(String toolName, String result) {
        return new AnalysisEvent(TYPE_TOOL_RESULT, toolName, result);
    }

    public static AnalysisEvent sql(String sql) {
        return new AnalysisEvent(TYPE_SQL, sql, null);
    }

    public static AnalysisEvent data(String jsonData) {
        return new AnalysisEvent(TYPE_DATA, jsonData, null);
    }

    public static AnalysisEvent chart(String chartOption) {
        return new AnalysisEvent(TYPE_CHART, chartOption, null);
    }

    public static AnalysisEvent analysis(String analysisText) {
        return new AnalysisEvent(TYPE_ANALYSIS, analysisText, null);
    }

    public static AnalysisEvent done(DataAnalysisResponse response) {
        try {
            String json = MAPPER.writeValueAsString(response);
            return new AnalysisEvent(TYPE_DONE, "分析完成", json);
        } catch (JacksonException e) {
            return new AnalysisEvent(TYPE_DONE, "分析完成", "{}");
        }
    }

    public static AnalysisEvent error(String errorMessage) {
        return new AnalysisEvent(TYPE_ERROR, errorMessage, null);
    }
}
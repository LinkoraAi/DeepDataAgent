package com.linkroa.deepdataagent.agent.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 分析结果值对象
 */
public record DataAnalysisResult(
    String sql,
    List<Map<String, Object>> queryData,
    ChartConfig chart,
    String analysis
) {
    public static DataAnalysisResult empty() {
        return new DataAnalysisResult("", List.of(), ChartConfig.empty(), "无分析结果");
    }
}

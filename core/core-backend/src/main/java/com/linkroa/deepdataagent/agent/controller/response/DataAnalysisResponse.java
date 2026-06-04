package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisResult;

import java.util.List;
import java.util.Map;

/**
 * 数据分析响应 DTO
 */
public record DataAnalysisResponse(
    String sql,
    List<Map<String, Object>> data,
    String chartType,
    String chartOption,
    String analysis,
    /** 标记查询结果是否为空 */
    boolean isEmptyResult
) {
    public static DataAnalysisResponse from(DataAnalysisResult result) {
        boolean isEmpty = result.queryData() == null || result.queryData().isEmpty();
        return new DataAnalysisResponse(
                result.sql(),
                result.queryData(),
                result.chart().chartType().name(),
                result.chart().echartsOption(),
                result.analysis(),
                isEmpty
        );
    }
}

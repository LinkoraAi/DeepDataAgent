package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 图表配置值对象
 */
public record ChartConfig(
    ChartType chartType,
    String echartsOption,
    String title,
    String description
) {
    public static ChartConfig empty() {
        return new ChartConfig(ChartType.TABLE, "{}", "数据表格", "暂无数据");
    }
}

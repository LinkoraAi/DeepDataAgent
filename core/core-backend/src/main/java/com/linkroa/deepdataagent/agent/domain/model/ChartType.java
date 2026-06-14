package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 图表类型枚举
 */
public enum ChartType {
    BAR("柱状图"),
    LINE("折线图"),
    PIE("饼图"),
    SCATTER("散点图"),
    TABLE("数据表格");

    private final String displayName;

    ChartType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

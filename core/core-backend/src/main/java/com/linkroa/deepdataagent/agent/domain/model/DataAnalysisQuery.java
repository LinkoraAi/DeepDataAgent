package com.linkroa.deepdataagent.agent.domain.model;

import org.springframework.util.ObjectUtils;

/**
 * 数据分析查询值对象
 */
public record DataAnalysisQuery(
    Long modelConfigId,
    String connectionId,
    String userQuestion
) {
    public DataAnalysisQuery {
        if (modelConfigId == null) {
            throw new IllegalArgumentException("模型配置 ID 不能为空");
        }
        if (ObjectUtils.isEmpty(connectionId)) {
            throw new IllegalArgumentException("数据源 ID 不能为空");
        }
        if (ObjectUtils.isEmpty(userQuestion)) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
    }
}

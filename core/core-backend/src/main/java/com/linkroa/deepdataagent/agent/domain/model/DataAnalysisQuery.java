package com.linkroa.deepdataagent.agent.domain.model;

import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * 数据分析查询值对象
 */
public record DataAnalysisQuery(
    Long modelConfigId,
    String connectionId,
    String text
) {
    /** 用户问题最大长度（字符数） */
    public static final int MAX_QUESTION_LENGTH = 5000;

    public DataAnalysisQuery {
        if (modelConfigId == null) {
            throw new IllegalArgumentException("模型配置 ID 不能为空");
        }
        if (ObjectUtils.isEmpty(connectionId)) {
            throw new IllegalArgumentException("数据源 ID 不能为空");
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        if (text.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("用户问题长度不能超过 " + MAX_QUESTION_LENGTH + " 字符");
        }
    }
}
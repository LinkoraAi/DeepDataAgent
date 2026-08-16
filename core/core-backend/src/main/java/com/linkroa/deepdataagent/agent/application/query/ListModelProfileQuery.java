package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;

/**
 * 模型配置分页查询
 */
public record ListModelProfileQuery(
        String keyword,
        ModelProfileStatus status,
        int page,
        int size
) {
}
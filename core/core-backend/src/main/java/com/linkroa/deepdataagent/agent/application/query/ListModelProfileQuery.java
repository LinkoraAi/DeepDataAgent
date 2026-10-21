package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;

import java.util.List;

/**
 * 模型配置分页查询
 *
 * @param keyword  名称关键字（可空）
 * @param status   状态过滤（可空）
 * @param modelTypes 类型业务码过滤（可空；空表示不限类型，多值为并集语义）
 * @param page     页码（1 起）
 * @param size     每页条数
 */
public record ListModelProfileQuery(
        String keyword,
        ModelProfileStatus status,
        List<Integer> modelTypes,
        int page,
        int size
) {
}

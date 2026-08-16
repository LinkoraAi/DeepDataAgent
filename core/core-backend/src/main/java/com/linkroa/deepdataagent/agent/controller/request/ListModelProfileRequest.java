package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 模型配置分页查询请求
 */
public record ListModelProfileRequest(
        String keyword,
        /** 状态（ENABLED / DISABLED） */
        String status,
        /** 页码（默认1） */
        Integer page,
        /** 每页大小（默认20） */
        Integer size
) {
}
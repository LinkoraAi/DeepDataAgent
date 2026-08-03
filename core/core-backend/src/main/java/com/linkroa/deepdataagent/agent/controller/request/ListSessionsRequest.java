package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 列出会话请求 DTO
 * <p>支持分页查询，不传参数时使用默认分页大小。</p>
 *
 * @param limit  每页数量，不传则使用默认配置
 * @param offset 偏移量，用于分页
 */
public record ListSessionsRequest(Integer limit, Integer offset) {
}

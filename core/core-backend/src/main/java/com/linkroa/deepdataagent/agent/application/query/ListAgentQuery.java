package com.linkroa.deepdataagent.agent.application.query;

/**
 * Agent 分页查询
 */
public record ListAgentQuery(
        String keyword,
        boolean includeArchived,
        int page,
        int size
) {
}
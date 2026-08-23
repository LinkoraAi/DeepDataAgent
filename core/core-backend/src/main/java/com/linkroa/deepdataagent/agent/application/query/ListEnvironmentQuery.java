package com.linkroa.deepdataagent.agent.application.query;

/**
 * 运行环境分页查询
 */
public record ListEnvironmentQuery(
        int page,
        int size
) {
}
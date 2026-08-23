package com.linkroa.deepdataagent.memory.application.query;

/**
 * 记忆库分页查询
 */
public record ListMemoryStoreQuery(
        int page,
        int size
) {
}
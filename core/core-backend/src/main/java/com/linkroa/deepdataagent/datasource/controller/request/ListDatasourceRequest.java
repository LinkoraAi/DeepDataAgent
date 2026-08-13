package com.linkroa.deepdataagent.datasource.controller.request;

/**
 * 列表查询请求
 */
public record ListDatasourceRequest(
        String keyword,
        String type,
        String status,
        Integer page,
        Integer size
) {
}

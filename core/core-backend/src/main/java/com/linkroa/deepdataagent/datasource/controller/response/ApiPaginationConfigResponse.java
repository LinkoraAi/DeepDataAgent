package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * API分页配置响应
 */
public record ApiPaginationConfigResponse(
    String paginationType,
    String pageParamName,
    String sizeParamName,
    String cursorParamName,
    String cursorJsonPath,
    String totalCountJsonPath,
    Integer pageSize,
    Integer maxPages
) {
}

package com.linkroa.deepdataagent.datasource.controller.request;

public record ApiPaginationConfigRequest(
    String paginationType,
    String pageParamName,
    String sizeParamName,
    String totalCountJsonPath,
    Integer pageSize,
    Integer maxPages
) {}

package com.linkroa.deepdataagent.datasource.controller.request;

public record ApiPaginationConfigRequest(
    String paginationType,
    String pageParamName,
    String sizeParamName,
    String cursorParamName,
    String cursorJsonPath,
    String totalCountJsonPath,
    Integer pageSize,
    Integer maxPages
) {}

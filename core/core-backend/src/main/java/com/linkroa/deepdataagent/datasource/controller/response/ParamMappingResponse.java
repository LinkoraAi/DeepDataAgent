package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * 参数映射响应
 */
public record ParamMappingResponse(
    String paramName,
    String paramLocation,
    String jsonPath
) {}

package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;
import java.util.Map;

/**
 * 前置操作配置响应
 */
public record PreOperationConfigResponse(
    boolean enabled,
    String url,
    String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    List<ParamMappingResponse> paramMappings
) {}

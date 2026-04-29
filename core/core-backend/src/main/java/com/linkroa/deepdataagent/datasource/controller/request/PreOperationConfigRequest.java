package com.linkroa.deepdataagent.datasource.controller.request;

import java.util.List;
import java.util.Map;

/**
 * 前置操作配置请求对象
 */
public record PreOperationConfigRequest(
    Boolean enabled,
    String url,
    String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    List<ParamMappingRequest> paramMappings
) {}

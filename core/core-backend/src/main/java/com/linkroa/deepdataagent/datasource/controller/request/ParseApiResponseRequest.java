package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ParseApiResponseRequest(
    Long connectionId,

    @NotBlank(message = "API请求地址不能为空")
    String url,

    String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,

    @NotBlank(message = "JsonPath根路径不能为空")
    String rootPath,
    Integer timeout,
    Integer retryCount,
    ApiAuthConfigRequest authConfig,
    ApiPaginationConfigRequest paginationConfig,
    List<PreOperationConfigRequest> preOperationConfigs
) {}

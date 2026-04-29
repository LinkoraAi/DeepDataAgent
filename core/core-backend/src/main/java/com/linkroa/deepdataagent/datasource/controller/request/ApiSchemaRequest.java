package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ApiSchemaRequest(
    @NotBlank(message = "API表名称(name)不能为空") String name,
    @NotBlank(message = "API请求地址(url)不能为空") String url,
    @NotBlank(message = "HTTP请求方法(method)不能为空") String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    String jsonPathConfig,
    Integer timeout,
    Integer retryCount,
    ApiAuthConfigRequest authConfig,
    ApiPaginationConfigRequest paginationConfig,
    List<PreOperationConfigRequest> preOperationConfigs,
    List<ApiFieldRequest> fields
) {}

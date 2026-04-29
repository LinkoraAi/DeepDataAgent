package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ApiConfigRequest(
    @NotBlank(message = "API请求地址不能为空")
    String apiUrl,

    @NotBlank(message = "API请求方法不能为空")
    String apiMethod,
    Map<String, String> apiHeaders,
    Map<String, String> apiParams,
    String apiBody,
    ApiAuthConfigRequest authConfig,
    ApiPaginationConfigRequest paginationConfig,
    Integer apiTimeout,
    
    String schemaName,
    String schemaPath,
    String schemaMethod,
    String jsonPathConfig,
    List<ApiFieldRequest> fields
) {}

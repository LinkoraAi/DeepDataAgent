package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record UpdateApiSchemaRequest(
    @NotNull Long schemaId,
    String name,
    String url,
    String method,
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

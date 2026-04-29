package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

import java.util.List;
import java.util.Map;

public record ApiSchemaCommand(
    String name,
    HttpMethod method,
    String url,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    String jsonPathConfig,
    Integer timeout,
    Integer retryCount,
    ApiAuthType authType,
    String authUsername,
    String authPassword,
    String paginationType,
    String pageSizeParamName,
    String pageNumberParamName,
    String totalCountJsonPath,
    Integer pageSize,
    Integer maxPages,
    List<PreOperationConfig> preOperationConfigs,
    List<ApiFieldCommand> fields
) {}

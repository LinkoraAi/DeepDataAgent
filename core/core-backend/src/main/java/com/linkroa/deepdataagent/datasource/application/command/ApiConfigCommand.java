package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

import java.util.List;
import java.util.Map;

public record ApiConfigCommand(
    String apiUrl,
    HttpMethod apiMethod,
    Map<String, String> apiHeaders,
    Map<String, String> apiParams,
    String apiBody,
    ApiAuthType apiAuthType,
    String apiAuthToken,
    String apiAuthUsername,
    String apiAuthPassword,
    String apiPaginationType,
    String apiPageSizeParamName,
    String apiPageNumberParamName,
    String apiCursorParamName,
    String apiCursorJsonPath,
    String apiTotalCountJsonPath,
    Integer apiPageSize,
    Integer apiMaxPages,
    Integer apiTimeout,
    String apiJsonPath,
    
    String schemaName,
    String schemaPath,
    HttpMethod schemaMethod,
    String jsonPathConfig,
    List<ApiFieldCommand> fields
) {}

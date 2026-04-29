package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record TestPreOperationRequest(
    @NotBlank String url,
    @NotBlank String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    ApiAuthConfigRequest authConfig
) {}

package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * API Schema详情响应
 */
public record ApiSchemaDetailResponse(
    Long id,
    Long connectionId,
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
    ApiAuthConfigResponse authConfig,
    ApiPaginationConfigResponse paginationConfig,
    List<PreOperationConfigResponse> preOperationConfigs,
    List<ApiFieldResponse> fields,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}

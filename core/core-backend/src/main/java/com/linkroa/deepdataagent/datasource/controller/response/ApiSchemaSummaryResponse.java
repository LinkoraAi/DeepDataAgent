package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.LocalDateTime;

/**
 * API Schema摘要响应
 */
public record ApiSchemaSummaryResponse(
    Long id,
    Long connectionId,
    String name,
    String url,
    String method,
    String bodyType,
    String jsonPathConfig,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

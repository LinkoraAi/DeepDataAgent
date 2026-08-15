package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

import java.time.OffsetDateTime;

/**
 * API Schema领域模型
 */
public record ApiSchema(
        Long id,
        Long connectionId,
        String name,
        String url,
        HttpMethod method,
        ApiRequestConfig config,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

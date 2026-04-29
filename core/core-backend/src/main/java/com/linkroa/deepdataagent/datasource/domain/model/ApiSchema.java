package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

import java.time.LocalDateTime;

/**
 * API Schema领域模型
 */
public record ApiSchema(
        Long id,
        Long connectionId,
        String name,
        String path,
        HttpMethod method,
        String jsonPathConfig,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

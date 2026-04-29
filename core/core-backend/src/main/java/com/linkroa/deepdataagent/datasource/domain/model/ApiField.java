package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.LocalDateTime;

/**
 * API字段领域模型
 */
public record ApiField(
        Long id,
        Long apiSchemaId,
        String originalName,
        String displayName,
        String jsonPath,
        String fieldType,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

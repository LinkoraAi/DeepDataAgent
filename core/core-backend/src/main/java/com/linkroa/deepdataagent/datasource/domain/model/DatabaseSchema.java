package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.LocalDateTime;

/**
 * 数据库Schema领域模型
 */
public record DatabaseSchema(
        Long id,
        Long connectionId,
        String schemaName,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

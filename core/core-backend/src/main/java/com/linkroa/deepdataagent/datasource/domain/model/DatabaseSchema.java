package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.OffsetDateTime;

/**
 * 数据库Schema领域模型
 */
public record DatabaseSchema(
        Long id,
        Long connectionId,
        String schemaName,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

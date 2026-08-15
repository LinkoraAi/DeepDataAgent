package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.OffsetDateTime;

/**
 * 表信息领域模型
 */
public record TableInfo(
        Long id,
        Long databaseSchemaId,
        String tableName,
        String tableComment,
        String tableCustomComment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

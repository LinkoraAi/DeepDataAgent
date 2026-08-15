package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.OffsetDateTime;

/**
 * 列信息领域模型
 */
public record ColumnInfo(
        Long id,
        Long tableId,
        String columnName,
        String dataType,
        String columnComment,
        String columnCustomComment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

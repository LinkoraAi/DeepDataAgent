package com.linkroa.deepdataagent.datasource.domain.model;

import java.time.LocalDateTime;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

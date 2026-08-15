package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 列信息响应
 */
public record ColumnInfoResponse(
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

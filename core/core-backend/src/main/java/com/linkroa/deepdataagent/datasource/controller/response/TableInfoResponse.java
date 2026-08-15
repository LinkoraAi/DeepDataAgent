package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 表信息响应
 */
public record TableInfoResponse(
        Long id,
        Long databaseSchemaId,
        String tableName,
        String tableComment,
        String tableCustomComment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

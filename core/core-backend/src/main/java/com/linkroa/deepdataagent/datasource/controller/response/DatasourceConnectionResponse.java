package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 数据源连接响应
 */
public record DatasourceConnectionResponse(
        Long id,
        String name,
        String type,
        String subType,
        String status,
        String host,
        Integer port,
        String database,
        String schema,
        String username,
        String maskedPassword,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy
) {
}

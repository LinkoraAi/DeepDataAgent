package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;

import java.time.LocalDateTime;

/**
 * 表信息响应
 */
public record TableInfoResponse(
        Long id,
        Long databaseSchemaId,
        String tableName,
        String tableComment,
        String tableCustomComment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TableInfoResponse from(TableInfo tableInfo) {
        return new TableInfoResponse(
                tableInfo.id(),
                tableInfo.databaseSchemaId(),
                tableInfo.tableName(),
                tableInfo.tableComment(),
                tableInfo.tableCustomComment(),
                tableInfo.createdAt(),
                tableInfo.updatedAt()
        );
    }
}

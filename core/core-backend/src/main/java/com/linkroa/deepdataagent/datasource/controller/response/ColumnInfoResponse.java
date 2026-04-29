package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;

import java.time.LocalDateTime;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ColumnInfoResponse from(ColumnInfo columnInfo) {
        return new ColumnInfoResponse(
                columnInfo.id(),
                columnInfo.tableId(),
                columnInfo.columnName(),
                columnInfo.dataType(),
                columnInfo.columnComment(),
                columnInfo.columnCustomComment(),
                columnInfo.createdAt(),
                columnInfo.updatedAt()
        );
    }

    /**
     * 从 API Field 创建响应
     */
    public static ColumnInfoResponse fromApiField(com.linkroa.deepdataagent.datasource.domain.model.ApiField apiField) {
        return new ColumnInfoResponse(
                apiField.id(),
                apiField.apiSchemaId(),
                apiField.originalName(),
                apiField.fieldType(),
                apiField.description(),
                null,
                apiField.createdAt(),
                apiField.updatedAt()
        );
    }
}

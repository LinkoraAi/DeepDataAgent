package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 表元数据响应体。
 *
 * @param id                 表 id
 * @param databaseSchemaId   所属 schema id
 * @param tableName          表名
 * @param tableComment       原始表注释
 * @param tableCustomComment 自定义表注释（优先展示）
 * @param createdAt          创建时间
 * @param updatedAt          更新时间
 * @author DeepDataAgent
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
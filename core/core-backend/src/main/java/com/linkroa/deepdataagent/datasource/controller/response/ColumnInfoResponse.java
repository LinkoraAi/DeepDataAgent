package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 字段元数据响应体。
 *
 * @param id                  字段 id
 * @param tableId             所属表 id
 * @param columnName          字段名
 * @param dataType            数据类型
 * @param columnComment       原始字段注释
 * @param columnCustomComment 自定义字段注释（优先展示）
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 * @author DeepDataAgent
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
package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一的数据表响应
 * <p>通过 type 字段区分数据源类型，不同字段在不同类型下可能为 null。</p>
 */
public record TableResponse(
    Long id,
    String type,
    Long databaseSchemaId,
    Long connectionId,
    String tableName,
    String tableComment,
    String tableCustomComment,
    String description,
    String url,
    String method,
    String jsonPath,
    List<ApiFieldResponse> fields,
    ApiPaginationConfigResponse paginationConfig,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}

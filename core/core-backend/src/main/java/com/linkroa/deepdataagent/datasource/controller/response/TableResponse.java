package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.LocalDateTime;
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
    String path,
    String method,
    String jsonPath,
    List<ApiFieldResponse> fields,
    ApiPaginationConfigResponse paginationConfig,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * 从 JDBC TableInfo 创建响应
     */
    public static TableResponse fromTableInfo(com.linkroa.deepdataagent.datasource.domain.model.TableInfo tableInfo) {
        return new TableResponse(
                tableInfo.id(),
                "JDBC",
                tableInfo.databaseSchemaId(),
                null,
                tableInfo.tableName(),
                tableInfo.tableComment(),
                tableInfo.tableCustomComment(),
                null,
                null,
                null,
                null,
                null,
                null,
                tableInfo.createdAt(),
                tableInfo.updatedAt()
        );
    }

    /**
     * 从 API Schema 创建响应
     */
    public static TableResponse fromApiSchema(com.linkroa.deepdataagent.datasource.domain.model.ApiSchema apiSchema) {
        return new TableResponse(
                apiSchema.id(),
                "API",
                null,
                apiSchema.connectionId(),
                apiSchema.name(),
                null,
                null,
                null,
                apiSchema.path(),
                apiSchema.method() != null ? apiSchema.method().name() : null,
                apiSchema.jsonPathConfig(),
                null,
                null,
                apiSchema.createdAt(),
                apiSchema.updatedAt()
        );
    }
}

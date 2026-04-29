package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;

import java.time.LocalDateTime;

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
        String username,
        String apiUrl,
        String apiMethod,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy
) {
    public static DatasourceConnectionResponse from(DatasourceConnection connection) {
        String host = null;
        Integer port = null;
        String database = null;
        String username = null;
        String apiUrl = null;
        String apiMethod = null;

        if (connection.jdbcConnectionConfig() != null) {
            host = connection.jdbcConnectionConfig().host();
            port = connection.jdbcConnectionConfig().port();
            database = connection.jdbcConnectionConfig().database();
            username = connection.jdbcConnectionConfig().username();
        }
        if (connection.apiConnectionConfig() != null) {
            apiUrl = connection.apiConnectionConfig().url();
            apiMethod = connection.apiConnectionConfig().method() != null ? connection.apiConnectionConfig().method().name() : null;
        }

        return new DatasourceConnectionResponse(
                connection.id(),
                connection.name(),
                connection.type().name(),
                connection.subType() != null ? connection.subType().name() : null,
                connection.status().name(),
                host,
                port,
                database,
                username,
                apiUrl,
                apiMethod,
                connection.description(),
                connection.createdAt(),
                connection.updatedAt(),
                connection.createdBy()
        );
    }
}

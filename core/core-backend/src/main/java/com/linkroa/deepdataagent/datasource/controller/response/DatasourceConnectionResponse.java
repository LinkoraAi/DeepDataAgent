package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import org.apache.commons.lang3.StringUtils;

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
        String maskedPassword,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy
) {
    /** 固定掩码文本，表示已配置密码 */
    private static final String MASKED_PASSWORD = "****...****";

    public static DatasourceConnectionResponse from(DatasourceConnection connection) {
        String host = null;
        Integer port = null;
        String database = null;
        String username = null;
        String maskedPassword = null;

        if (connection.jdbcConnectionConfig() != null) {
            host = connection.jdbcConnectionConfig().host();
            port = connection.jdbcConnectionConfig().port();
            database = connection.jdbcConnectionConfig().database();
            username = connection.jdbcConnectionConfig().username();
            // 密码已配置时返回固定掩码，前端据此判断是否需要用户重新输入
            if (StringUtils.isNotBlank(connection.jdbcConnectionConfig().password())) {
                maskedPassword = MASKED_PASSWORD;
            }
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
                maskedPassword,
                connection.description(),
                connection.createdAt(),
                connection.updatedAt(),
                connection.createdBy()
        );
    }
}

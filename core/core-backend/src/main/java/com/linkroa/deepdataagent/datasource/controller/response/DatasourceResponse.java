package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;

/**
 * 数据源连接响应体（**不含密码**）。
 *
 * @param id          连接 id
 * @param name        连接名称
 * @param type        主类型（{@code JDBC} / {@code API}）
 * @param subType     JDBC 子类型（API 类型为 null）
 * @param status      状态（{@code ENABLED} / {@code DISABLED}）
 * @param jdbcConfig  JDBC 连接配置（脱敏，API 类型为 null）
 * @param description 描述
 * @param createdAt   创建时间（带时区偏移）
 * @param updatedAt   更新时间（带时区偏移）
 * @author DeepDataAgent
 */
public record DatasourceResponse(
        Long id,
        String name,
        String type,
        String subType,
        String status,
        JdbcConfigResponse jdbcConfig,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
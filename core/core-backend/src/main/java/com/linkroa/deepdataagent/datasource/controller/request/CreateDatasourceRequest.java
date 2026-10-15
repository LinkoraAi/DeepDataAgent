package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建数据源请求体。
 *
 * @param name        数据源名称（必填，1-100 字符，全局唯一）
 * @param type        主类型（必填，{@code JDBC} / {@code API}）
 * @param subType     JDBC 子类型（{@code MYSQL} / {@code CLICKHOUSE} / {@code POSTGRESQL}；API 类型可为空）
 * @param description 描述（可空，≤500 字符）
 * @param jdbcConfig  JDBC 连接配置（JDBC 类型必填）
 * @param apiSchemas  API 数据表配置列表（API 类型可选，允许创建时内嵌多张表）
 * @author DeepDataAgent
 */
public record CreateDatasourceRequest(
        @NotBlank(message = "数据源名称不能为空")
        @Size(max = 100, message = "数据源名称不能超过100个字符")
        String name,

        @NotBlank(message = "数据源类型不能为空")
        String type,

        String subType,

        @Size(max = 500, message = "描述不能超过500个字符")
        String description,

        @Valid
        JdbcConfigRequest jdbcConfig,

        List<@Valid ApiSchemaRequest> apiSchemas
) {
}
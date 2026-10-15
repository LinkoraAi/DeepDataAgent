package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * 编辑数据源请求体（增量语义：空白字段不覆盖；类型与子类型不可修改）。
 * <p>JDBC 连接配置若携带，则按整体替换处理（密码留空表示沿用原密码）。</p>
 *
 * @param name        新名称（可空，≤100 字符，全局唯一）
 * @param description 新描述（可空，≤500 字符）
 * @param jdbcConfig  新 JDBC 连接配置（可空）
 * @author DeepDataAgent
 */
public record UpdateDatasourceRequest(
        @Size(max = 100, message = "数据源名称不能超过100个字符")
        String name,

        @Size(max = 500, message = "描述不能超过500个字符")
        String description,

        @Valid
        JdbcConfigRequest jdbcConfig
) {
}
package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * JDBC 连接配置请求体。
 *
 * @param host     主机名 / IP（必填）
 * @param port     端口（1-65535）
 * @param database 数据库名（必填）
 * @param username 用户名（必填）
 * @param password 密码（编辑场景留空表示沿用原密码）
 * @param schema   schema（PostgreSQL 适用；留空按 public 处理，仅允许单个值）
 * @author DeepDataAgent
 */
public record JdbcConfigRequest(
        @NotBlank(message = "主机名不能为空")
        String host,

        @Min(value = 1, message = "端口必须在1-65535之间")
        @Max(value = 65535, message = "端口必须在1-65535之间")
        Integer port,

        @NotBlank(message = "数据库名称不能为空")
        String database,

        @NotBlank(message = "用户名不能为空")
        String username,

        String password,

        String schema
) {
}
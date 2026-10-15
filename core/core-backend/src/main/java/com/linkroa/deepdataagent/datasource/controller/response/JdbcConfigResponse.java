package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * JDBC 连接配置响应体（**不含 password 字段**，密码在任何响应中均不回显）。
 *
 * @param host     主机名 / IP
 * @param port     端口
 * @param database 数据库名
 * @param username 用户名
 * @param schema   schema
 * @author DeepDataAgent
 */
public record JdbcConfigResponse(
        String host,
        Integer port,
        String database,
        String username,
        String schema
) {
}
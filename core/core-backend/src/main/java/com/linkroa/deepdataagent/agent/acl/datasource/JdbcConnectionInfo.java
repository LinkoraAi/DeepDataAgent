package com.linkroa.deepdataagent.agent.acl.datasource;

/**
 * JDBC 连接信息（ACL 值对象）
 * <p>隔离 agent 模块对 datasource 模块 JdbcConnectionConfig 的直接依赖。</p>
 */
public record JdbcConnectionInfo(
    String host,
    int port,
    String database,
    String username,
    String password,
    String schema
) {
    public JdbcConnectionInfo {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("端口号必须大于 0");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
    }
}

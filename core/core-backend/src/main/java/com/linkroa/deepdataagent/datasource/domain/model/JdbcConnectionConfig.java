package com.linkroa.deepdataagent.datasource.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * JDBC连接配置值对象
 */
public record JdbcConnectionConfig(
        String host,
        int port,
        String database,
        String username,
        String password
) {
    public JdbcConnectionConfig {
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口号必须在1到65535之间");
        }
        if (StringUtils.isBlank(database)) {
            throw new IllegalArgumentException("数据库名称不能为空");
        }
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (StringUtils.isBlank(password)) {
            throw new IllegalArgumentException("密码不能为空");
        }
    }
}

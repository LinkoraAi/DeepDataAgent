package com.linkroa.deepdataagent.agent.acl.datasource;

/**
 * JDBC 类型分类（ACL 值对象）
 * <p>隔离 agent 模块对 datasource 模块 JdbcType 的直接依赖。</p>
 */
public enum JdbcCategory {
    MYSQL,
    CLICKHOUSE
}

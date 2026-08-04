package com.linkroa.deepdataagent.datasource.infrastructure.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽象 JDBC 连接策略的异常诊断测试
 * <p>覆盖 diagnoseSqlException 对常见连接失败场景的 SQLState 识别与中文提示转换。</p>
 */
class AbstractJdbcConnectionStrategyTest {

    private AbstractJdbcConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MysqlConnectionStrategy();
    }

    @Test
    void should_returnAuthFailure_when_diagnoseSqlException_given_pgInvalidPasswordState() {
        // given PostgreSQL 密码认证失败 SQLState 28P01
        SQLException ex = new SQLException("psql: FATAL: password authentication failed for user", "28P01");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then
        assertTrue(message.contains("身份认证失败"));
    }

    @Test
    void should_returnAuthFailure_when_diagnoseSqlException_given_genericAuthState() {
        // given 通用认证失败 SQLState 28000
        SQLException ex = new SQLException("Access denied", "28000");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then
        assertTrue(message.contains("身份认证失败"));
    }

    @Test
    void should_returnNetworkFailure_when_diagnoseSqlException_given_connectionState() {
        // given 网络类错误 SQLState 以 08 开头
        SQLException ex = new SQLException("Connection refused", "08001");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then
        assertTrue(message.contains("网络连接失败"));
    }

    @Test
    void should_returnSchemaNotFound_when_diagnoseSqlException_given_pgInvalidCatalogState() {
        // given PostgreSQL schema 不存在 SQLState 3D000
        SQLException ex = new SQLException("schema \"public\" does not exist", "3D000");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then
        assertTrue(message.contains("schema 不存在"));
    }

    @Test
    void should_returnSchemaNoPrivilege_when_diagnoseSqlException_given_pgInsufficientPrivilegeState() {
        // given PostgreSQL 无权限 SQLState 42501
        SQLException ex = new SQLException("permission denied for schema analytics", "42501");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then
        assertTrue(message.contains("无访问权限"));
    }

    @Test
    void should_returnRawMessage_when_diagnoseSqlException_given_unknownState() {
        // given 无法识别的 SQLState
        SQLException ex = new SQLException("some unknown error", "XX000");

        // when
        String message = strategy.diagnoseSqlException(ex);

        // then 兜底返回原始错误信息
        assertTrue(message.contains("some unknown error"));
    }
}
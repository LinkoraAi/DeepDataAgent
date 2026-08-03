package com.linkroa.deepdataagent.agent.domain.service.port;

/**
 * SQL 校验端口
 * <p>领域层端口接口，用于抽象 SQL 安全校验能力，由基础设施层实现。</p>
 */
public interface SqlValidationPort {

    /**
     * 校验 SQL 语句安全性
     *
     * @param sql 待校验的 SQL 语句
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 如果 SQL 不安全
     */
    void validate(String sql);
}
package com.linkroa.deepdataagent.agent.domain.service.port;

/**
 * NL2SQL 生成端口
 * <p>领域层端口接口，用于抽象将自然语言转换为 SQL 的能力，由基础设施层实现。</p>
 */
public interface NL2SqlPort {

    /**
     * 将自然语言转换为 SQL 查询语句
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @param sessionId     会话 ID（用于流式回调，可为 null）
     * @return 生成的 SQL 语句
     */
    String generateSql(Long modelConfigId, String userQuestion, String schemaInfo, String sqlDialect,
                       String sessionId);
}
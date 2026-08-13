package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.service.port.NL2SqlPort;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NL2SQL 转换服务
 * <p>将用户自然语言转换为 SQL 查询，支持自动重试修正机制。
 * 依赖领域层端口接口 {@link NL2SqlPort} 与 {@link SqlValidationPort}，实现与具体实现的解耦。
 * 该领域服务由基础设施层 {@code DomainServiceConfig} 通过 {@code @Bean} 装配。</p>
 */
public class NL2SqlService {

    private static final Logger log = LoggerFactory.getLogger(NL2SqlService.class);

    private final NL2SqlPort nl2SqlPort;
    private final SqlValidationPort sqlValidationPort;
    private final int maxRetryCount;

    public NL2SqlService(NL2SqlPort nl2SqlPort, SqlValidationPort sqlValidationPort,
                         int maxRetryCount) {
        this.nl2SqlPort = nl2SqlPort;
        this.sqlValidationPort = sqlValidationPort;
        this.maxRetryCount = maxRetryCount;
    }

    /**
     * 将自然语言转换为 SQL
     *
     * @param modelConfigId 模型配置 ID
     * @param text          用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @return 生成的 SQL 语句
     * @throws DeepDataAgentException 如果转换失败
     */
    public String convert(Long modelConfigId, String text, String schemaInfo, String sqlDialect) {
        return convert(modelConfigId, text, schemaInfo, sqlDialect, null);
    }

    /**
     * 将自然语言转换为 SQL（带 sessionId 用于流式回调）
     *
     * @param modelConfigId 模型配置 ID
     * @param text          用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @param sessionId     会话 ID（用于获取 delta 回调，可为 null）
     * @return 生成的 SQL 语句
     * @throws DeepDataAgentException 如果转换失败
     */
    public String convert(Long modelConfigId, String text, String schemaInfo, String sqlDialect, String sessionId) {
        String context = schemaInfo;

        for (int attempt = 1; attempt <= maxRetryCount; attempt++) {
            String sql = null;
            try {
                sql = nl2SqlPort.generateSql(modelConfigId, text, context, sqlDialect, sessionId);
                sqlValidationPort.validate(sql);
                log.info("NL2SQL 成功 (尝试 {} 次): {}", attempt, sql);
                return sql;
            } catch (Exception e) {
                // sql 非空表示 LLM 已生成 SQL 但校验未通过：把违规 SQL 与原因回传给 LLM，便于其自纠
                // sql 为空表示生成阶段本身失败：仅回传错误原因
                String guidance = sql != null
                        ? "你上次生成的 SQL 不合规，请修正后重新生成：\n上次 SQL: " + sql
                          + "\n错误: " + e.getMessage()
                          + "\n修正要求: 只输出单条 SELECT 语句；分号（;）最多一个且只能出现在语句末尾；不要输出任何解释、注释或 markdown 标记。"
                        : "生成失败: " + e.getMessage();
                context = schemaInfo + "\n\n" + guidance;
                log.warn("NL2SQL 失败 (尝试 {}/{}): {}", attempt, maxRetryCount, e.getMessage());
            }
        }

        throw new DeepDataAgentException("NL2SQL 转换失败，已重试 " + maxRetryCount + " 次");
    }
}
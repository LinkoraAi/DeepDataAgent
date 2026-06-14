package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.adapter.SqlValidator;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Text-to-SQL 转换服务
 * <p>将用户自然语言转换为 SQL 查询，支持自动重试修正机制。</p>
 */
@Service
public class TextToSqlService {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlService.class);

    private final LLMClient llmClient;
    private final SqlValidator sqlValidator;
    private final int maxRetryCount;

    public TextToSqlService(LLMClient llmClient, SqlValidator sqlValidator, DataAnalysisProperties properties) {
        this.llmClient = llmClient;
        this.sqlValidator = sqlValidator;
        this.maxRetryCount = properties.getMaxRetryCount();
    }

    /**
     * 将自然语言转换为 SQL
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @return 生成的 SQL 语句
     * @throws DataAnalysisException 如果转换失败
     */
    public String convert(Long modelConfigId, String userQuestion, String schemaInfo, String sqlDialect) {
        String context = schemaInfo;

        for (int attempt = 1; attempt <= maxRetryCount; attempt++) {
            try {
                String sql = llmClient.generateSQL(modelConfigId, userQuestion, context, sqlDialect);
                sqlValidator.validate(sql);
                log.info("Text-to-SQL 成功 (尝试 {} 次): {}", attempt, sql);
                return sql;
            } catch (Exception e) {
                log.warn("Text-to-SQL 失败 (尝试 {}/{}): {}", attempt, maxRetryCount, e.getMessage());
                context = schemaInfo + "\n\n上次错误: " + e.getMessage();
            }
        }

        throw new DataAnalysisException("Text-to-SQL 转换失败，已重试 " + maxRetryCount + " 次");
    }
}

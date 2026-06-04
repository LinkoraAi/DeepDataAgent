package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 模块模型配置表初始化器
 * <p>在应用启动时自动创建 LLM 模型配置相关的数据库表结构和初始模板数据。</p>
 */
@Component
public class AgentModelSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentModelSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentModelSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        log.info("开始初始化 Agent 模块模型配置表结构...");
        createLlmModelTemplateTable();
        insertDefaultTemplates();
        createLlmModelConfigTable();
        log.info("Agent 模块模型配置表结构初始化完成");
    }

    private void createLlmModelTemplateTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS llm_model_template (
                    id              INTEGER       PRIMARY KEY AUTOINCREMENT,
                    provider        TEXT          NOT NULL,
                    model_name      TEXT,
                    display_name    TEXT          NOT NULL,
                    base_url        TEXT,
                    description     TEXT,
                    sort_order      INTEGER       NOT NULL DEFAULT 0,
                    is_enabled      INTEGER       NOT NULL DEFAULT 1,
                    created_at      TEXT          NOT NULL DEFAULT (datetime('now'))
                )
                """);
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model ON llm_model_template (provider, model_name) WHERE is_enabled = 1");
    }

    private void insertDefaultTemplates() {
        // 使用 INSERT OR IGNORE 避免重复插入
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order)
                VALUES ('dashscope', 'qwen-turbo', '通义千问 Turbo', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '快速响应，适合简单查询', 1)
                """);
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order)
                VALUES ('dashscope', 'qwen-plus', '通义千问 Plus', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '平衡性能和质量，推荐日常使用', 2)
                """);
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order)
                VALUES ('dashscope', 'qwen-max', '通义千问 Max', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '高质量，适合复杂分析', 3)
                """);
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order)
                VALUES ('deepseek', 'deepseek-chat', 'DeepSeek V3', 'https://api.deepseek.com/v1', '高性价比，支持多种场景', 4)
                """);
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order)
                VALUES ('openai', NULL, 'OpenAI 兼容（自定义）', NULL, '支持 SiliconFlow、Ollama 等兼容 OpenAI 格式的 API', 5)
                """);
    }

    private void createLlmModelConfigTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS llm_model_config (
                    id                  INTEGER       PRIMARY KEY AUTOINCREMENT,
                    name                TEXT          NOT NULL,
                    template_id         INTEGER       NOT NULL,
                    provider            TEXT          NOT NULL,
                    base_url            TEXT,
                    api_key             TEXT          NOT NULL,
                    model_name          TEXT          NOT NULL,
                    temperature         REAL          NOT NULL DEFAULT 0.1,
                    is_default          INTEGER       NOT NULL DEFAULT 0,
                    description         TEXT,
                    created_at          TEXT          NOT NULL DEFAULT (datetime('now')),
                    updated_at          TEXT          NOT NULL DEFAULT (datetime('now')),
                    is_deleted          INTEGER       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_name ON llm_model_config (name) WHERE is_deleted = 0");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_is_default ON llm_model_config (is_default) WHERE is_deleted = 0 AND is_default = 1");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_template_id ON llm_model_config (template_id) WHERE is_deleted = 0");
    }
}

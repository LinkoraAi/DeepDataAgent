package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Agent 模块数据库表结构初始化器
 * <p>合并了原来的 AgentSessionSchemaInitializer 和 AgentModelSchemaInitializer</p>
 */
@Component
public class AgentSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentSchemaInitializer.class);

    private final DataSource dataSource;

    public AgentSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing agent database schema...");
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // 1. 创建模型信息表（合并原 provider + info + config）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS agent_model_info (
                        id                    INTEGER       PRIMARY KEY AUTOINCREMENT,
                        provider_display_name TEXT          NOT NULL,
                        provider_name         TEXT          NOT NULL,
                        model_id              TEXT          NOT NULL,
                        api_url               TEXT          NOT NULL,
                        api_key               TEXT          NOT NULL,
                        is_default            INTEGER       NOT NULL DEFAULT 0,
                        is_enabled            INTEGER       NOT NULL DEFAULT 1,
                        sort_order            INTEGER       NOT NULL DEFAULT 0,
                        created_time          TEXT          NOT NULL DEFAULT (datetime('now')),
                        updated_time          TEXT          NOT NULL DEFAULT (datetime('now')),
                        is_deleted            INTEGER       NOT NULL DEFAULT 0
                    )
                """);

                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model ON agent_model_info(provider_name, model_id) WHERE is_deleted = 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_is_default ON agent_model_info(is_default) WHERE is_deleted = 0 AND is_default = 1");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_is_enabled ON agent_model_info(is_enabled) WHERE is_deleted = 0");

                // 2. 创建会话表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS agent_session (
                        id                TEXT          PRIMARY KEY,
                        title             TEXT          NOT NULL DEFAULT '新对话',
                        user_id           INTEGER,
                        datasource_id     INTEGER       NOT NULL,
                        model_config_id   INTEGER       NOT NULL,
                        status            TEXT          NOT NULL DEFAULT 'ACTIVE',
                        last_message_time TEXT,
                        created_time      TEXT          NOT NULL DEFAULT (datetime('now')),
                        updated_time      TEXT          NOT NULL DEFAULT (datetime('now')),
                        is_deleted        INTEGER       NOT NULL DEFAULT 0
                    )
                """);

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_user ON agent_session(user_id) WHERE is_deleted = 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_status ON agent_session(status) WHERE is_deleted = 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_datasource ON agent_session(datasource_id) WHERE is_deleted = 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_last_msg ON agent_session(last_message_time) WHERE is_deleted = 0");

                // 3. 创建对话轮次表（消息以 JSON 数组存储于 messages 字段，不再单独建消息表）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dialogue (
                        id              INTEGER       PRIMARY KEY AUTOINCREMENT,
                        session_id      TEXT          NOT NULL,
                        user_question   TEXT          NOT NULL,
                        messages        TEXT,
                        status          TEXT          NOT NULL DEFAULT 'PENDING',
                        metadata        TEXT,
                        start_time      TEXT          NOT NULL DEFAULT (datetime('now')),
                        end_time        TEXT,
                        is_deleted      INTEGER       NOT NULL DEFAULT 0
                    )
                """);

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_dialogue_session ON dialogue(session_id) WHERE is_deleted = 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_dialogue_status ON dialogue(status) WHERE is_deleted = 0");

                log.info("Agent database schema initialized successfully.");
            }
        } catch (Exception e) {
            log.error("Failed to initialize agent database schema", e);
            throw new RuntimeException("Failed to initialize agent database schema", e);
        }
    }
}

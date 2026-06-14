package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 会话与消息历史表初始化器
 * <p>在应用启动时自动创建 agent_session 和 conversation_msg 表结构。</p>
 */
@Component
public class AgentSessionSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentSessionSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        log.info("开始初始化 Agent 会话与消息历史表结构...");
        createAgentSessionTable();
        createConversationMsgTable();
        log.info("Agent 会话与消息历史表结构初始化完成");
    }

    private void createAgentSessionTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_session (
                    id                TEXT          PRIMARY KEY,
                    title             TEXT          NOT NULL DEFAULT '新对话',
                    datasource_id     INTEGER       NOT NULL,
                    model_config_id   INTEGER       NOT NULL,
                    status            TEXT          NOT NULL DEFAULT 'active',
                    message_count     INTEGER       NOT NULL DEFAULT 0,
                    last_message_at   TEXT,
                    created_at        TEXT          NOT NULL DEFAULT (datetime('now')),
                    updated_at        TEXT          NOT NULL DEFAULT (datetime('now')),
                    closed_at         TEXT,
                    is_deleted        INTEGER       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_session_status ON agent_session(status) WHERE is_deleted = 0");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_session_datasource ON agent_session(datasource_id) WHERE is_deleted = 0");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_session_last_msg ON agent_session(last_message_at) WHERE is_deleted = 0");
    }

    private void createConversationMsgTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversation_msg (
                    id                INTEGER       PRIMARY KEY AUTOINCREMENT,
                    session_id        TEXT          NOT NULL,
                    role              TEXT          NOT NULL,
                    content           TEXT          NOT NULL,
                    tool_calls        TEXT,
                    tool_result       TEXT,
                    metadata          TEXT,
                    created_at        TEXT          NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY (session_id) REFERENCES agent_session(id)
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_msg_session ON conversation_msg(session_id)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_msg_session_order ON conversation_msg(session_id, id)");
    }
}

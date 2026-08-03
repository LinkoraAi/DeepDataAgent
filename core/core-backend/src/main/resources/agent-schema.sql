-- ===========================================================
-- Agent 模块数据库架构
-- 数据库: SQLite
-- ===========================================================

-- ===========================================================
-- 第一部分：模型配置表
-- ===========================================================

-- 模型信息表（AgentModelInfo）：存储可用的 LLM 模型配置（OpenAI/DashScope/DeepSeek 等）
CREATE TABLE IF NOT EXISTS agent_model_info (
    id                    INTEGER       PRIMARY KEY AUTOINCREMENT,          -- 主键，自增
    provider_display_name TEXT          NOT NULL,                           -- 提供商展示名称（如 OpenAI）
    provider_name         TEXT          NOT NULL,                           -- 提供商标识（如 openai）
    model_id              TEXT          NOT NULL,                           -- 模型 ID（如 gpt-4o）
    api_url               TEXT          NOT NULL,                           -- API 地址
    api_key               TEXT          NOT NULL,                           -- API 密钥（加密存储）
    is_default            INTEGER       NOT NULL DEFAULT 0,                 -- 是否默认模型（1=是，0=否）
    is_enabled            INTEGER       NOT NULL DEFAULT 1,                 -- 是否启用（1=启用，0=停用）
    sort_order            INTEGER       NOT NULL DEFAULT 0,                 -- 排序权重
    created_time          TEXT          NOT NULL DEFAULT (datetime('now')), -- 创建时间
    updated_time          TEXT          NOT NULL DEFAULT (datetime('now')), -- 更新时间
    is_deleted            INTEGER       NOT NULL DEFAULT 0                  -- 逻辑删除标记（1=已删除，0=未删除）
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model ON agent_model_info(provider_name, model_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_is_default ON agent_model_info(is_default) WHERE is_deleted = 0 AND is_default = 1;
CREATE INDEX IF NOT EXISTS idx_is_enabled ON agent_model_info(is_enabled) WHERE is_deleted = 0;


-- ===========================================================
-- 第二部分：会话管理表
-- ===========================================================

-- 会话表（AgentSession）：存储一次用户会话的上下文信息
CREATE TABLE IF NOT EXISTS agent_session (
    id                TEXT          PRIMARY KEY,                             -- 主键（UUID）
    title             TEXT          NOT NULL DEFAULT '新对话',               -- 会话标题
    user_id           INTEGER,                                               -- 用户 ID（预留，暂未启用）
    datasource_id     INTEGER       NOT NULL,                                -- 关联的数据源 ID
    model_config_id   INTEGER       NOT NULL,                                -- 关联的模型配置 ID
    status            TEXT          NOT NULL DEFAULT 'ACTIVE',               -- 会话状态（ACTIVE=活跃）
    last_message_time TEXT,                                                  -- 最后一条消息时间
    created_time      TEXT          NOT NULL DEFAULT (datetime('now')),      -- 创建时间
    updated_time      TEXT          NOT NULL DEFAULT (datetime('now')),      -- 更新时间
    is_deleted        INTEGER       NOT NULL DEFAULT 0                       -- 逻辑删除标记（1=已删除，0=未删除）
);

CREATE INDEX IF NOT EXISTS idx_session_user ON agent_session(user_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_status ON agent_session(status) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_datasource ON agent_session(datasource_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_last_msg ON agent_session(last_message_time) WHERE is_deleted = 0;


-- ===========================================================
-- 第三部分：对话轮次表
-- ===========================================================

-- 对话轮次表（Dialogue）：一轮完整对话（用户提问 + Agent 全量回复）
-- 消息以 JSON 数组存储于 messages 字段，不再单独建消息表
CREATE TABLE IF NOT EXISTS dialogue (
    id              INTEGER       PRIMARY KEY AUTOINCREMENT,          -- 主键，自增
    session_id      TEXT          NOT NULL,                           -- 所属会话 ID（关联 agent_session.id）
    user_question   TEXT          NOT NULL,                           -- 用户问题
    messages        TEXT,                                             -- 消息列表（DialogueMessage JSON 数组：角色/类型/内容/状态/时间戳）
    status          TEXT          NOT NULL DEFAULT 'PENDING',         -- 对话状态（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED/DELETED）
    metadata        TEXT,                                             -- LLM 调用统计信息（预留：调用次数/token 用量/耗时），不存业务数据
    start_time      TEXT          NOT NULL DEFAULT (datetime('now')), -- 对话开始时间
    end_time        TEXT,                                             -- 对话结束时间
    is_deleted      INTEGER       NOT NULL DEFAULT 0                  -- 逻辑删除标记（1=已删除，0=未删除）
);

CREATE INDEX IF NOT EXISTS idx_dialogue_session ON dialogue(session_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dialogue_status ON dialogue(status) WHERE is_deleted = 0;

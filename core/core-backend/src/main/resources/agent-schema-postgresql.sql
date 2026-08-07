-- ===========================================================
-- Agent 模块数据库架构
-- 数据库: PostgreSQL
-- ===========================================================

-- ===========================================================
-- 第一部分：模型配置表
-- ===========================================================

CREATE TABLE IF NOT EXISTS agent_model_info (
    id                    BIGSERIAL     PRIMARY KEY,
    provider_display_name VARCHAR(100)  NOT NULL,
    provider_name         VARCHAR(50)   NOT NULL,
    model_id              VARCHAR(100)  NOT NULL,
    api_url               VARCHAR(500)  NOT NULL,
    api_key               VARCHAR(500)  NOT NULL,
    is_default            SMALLINT      NOT NULL DEFAULT 0,
    is_enabled            SMALLINT      NOT NULL DEFAULT 1,
    sort_order            INTEGER       NOT NULL DEFAULT 0,
    created_time          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted            SMALLINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE agent_model_info IS '模型信息表：存储可用的 LLM 模型配置（OpenAI/DashScope/DeepSeek 等）';
COMMENT ON COLUMN agent_model_info.id IS '主键，自增';
COMMENT ON COLUMN agent_model_info.provider_display_name IS '提供商展示名称（如 OpenAI）';
COMMENT ON COLUMN agent_model_info.provider_name IS '提供商标识（如 openai）';
COMMENT ON COLUMN agent_model_info.model_id IS '模型 ID（如 gpt-4o）';
COMMENT ON COLUMN agent_model_info.api_url IS 'API 地址';
COMMENT ON COLUMN agent_model_info.api_key IS 'API 密钥（加密存储）';
COMMENT ON COLUMN agent_model_info.is_default IS '是否默认模型（1=是，0=否）';
COMMENT ON COLUMN agent_model_info.is_enabled IS '是否启用（1=启用，0=停用）';
COMMENT ON COLUMN agent_model_info.sort_order IS '排序权重';
COMMENT ON COLUMN agent_model_info.created_time IS '创建时间';
COMMENT ON COLUMN agent_model_info.updated_time IS '更新时间';
COMMENT ON COLUMN agent_model_info.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model ON agent_model_info(provider_name, model_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_is_default ON agent_model_info(is_default) WHERE is_deleted = 0 AND is_default = 1;
CREATE INDEX IF NOT EXISTS idx_is_enabled ON agent_model_info(is_enabled) WHERE is_deleted = 0;

-- ===========================================================
-- 第二部分：会话管理表
-- ===========================================================

CREATE TABLE IF NOT EXISTS agent_session (
    id                VARCHAR(36)   PRIMARY KEY,
    title             VARCHAR(200)  NOT NULL DEFAULT '新对话',
    user_id           BIGINT,
    datasource_id     BIGINT        NOT NULL,
    model_config_id   BIGINT        NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_message_time TIMESTAMP,
    created_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted        SMALLINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE agent_session IS '会话表：存储一次用户会话的上下文信息';
COMMENT ON COLUMN agent_session.id IS '主键（UUID）';
COMMENT ON COLUMN agent_session.title IS '会话标题';
COMMENT ON COLUMN agent_session.user_id IS '用户 ID（预留，暂未启用）';
COMMENT ON COLUMN agent_session.datasource_id IS '关联的数据源 ID';
COMMENT ON COLUMN agent_session.model_config_id IS '关联的模型配置 ID';
COMMENT ON COLUMN agent_session.status IS '会话状态（ACTIVE=活跃）';
COMMENT ON COLUMN agent_session.last_message_time IS '最后一条消息时间';
COMMENT ON COLUMN agent_session.created_time IS '创建时间';
COMMENT ON COLUMN agent_session.updated_time IS '更新时间';
COMMENT ON COLUMN agent_session.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE INDEX IF NOT EXISTS idx_session_user ON agent_session(user_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_status ON agent_session(status) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_datasource ON agent_session(datasource_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_last_msg ON agent_session(last_message_time) WHERE is_deleted = 0;

-- ===========================================================
-- 第三部分：对话轮次表
-- ===========================================================

CREATE TABLE IF NOT EXISTS dialogue (
    id            BIGSERIAL   PRIMARY KEY,
    session_id    VARCHAR(36) NOT NULL,
    user_question TEXT        NOT NULL,
    messages      JSONB,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    metadata      JSONB,
    start_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time      TIMESTAMP,
    is_deleted    SMALLINT    NOT NULL DEFAULT 0
);

COMMENT ON TABLE dialogue IS '对话轮次表：一轮完整对话（用户提问 + Agent 全量回复），消息以 JSON 数组存储于 messages 字段';
COMMENT ON COLUMN dialogue.id IS '主键，自增';
COMMENT ON COLUMN dialogue.session_id IS '所属会话 ID（关联 agent_session.id）';
COMMENT ON COLUMN dialogue.user_question IS '用户问题';
COMMENT ON COLUMN dialogue.messages IS '消息列表（DialogueMessage JSON 数组：角色/类型/内容/状态/时间戳）';
COMMENT ON COLUMN dialogue.status IS '对话状态（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED/DELETED）';
COMMENT ON COLUMN dialogue.metadata IS 'LLM 调用统计信息（调用次数/token 用量/耗时），不存业务数据';
COMMENT ON COLUMN dialogue.start_time IS '对话开始时间';
COMMENT ON COLUMN dialogue.end_time IS '对话结束时间';
COMMENT ON COLUMN dialogue.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE INDEX IF NOT EXISTS idx_dialogue_session ON dialogue(session_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dialogue_status ON dialogue(status) WHERE is_deleted = 0;

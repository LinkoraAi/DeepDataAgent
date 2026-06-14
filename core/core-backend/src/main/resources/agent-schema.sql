-- ===========================================================
-- Agent 模块数据库架构
-- ===========================================================
-- 本文件包含 Agent 模块所需的所有数据库表结构定义，
-- 包括会话管理、消息历史和模型配置等。
-- 数据库: SQLite
-- ===========================================================


-- ===========================================================
-- 第一部分：模型配置相关表
-- ===========================================================

-- -----------------------------------------------------------
-- 1. 预置模型模板表
--    系统内置的模型模板，用户添加模型时从中选择
--    模板不可编辑、不可删除
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_model_template (
    id              INTEGER       PRIMARY KEY AUTOINCREMENT,
    provider        TEXT          NOT NULL,             -- 提供商: dashscope, deepseek, openai
    model_name      TEXT          NOT NULL,             -- 模型名称: qwen-plus, deepseek-chat
    display_name    TEXT          NOT NULL,             -- 显示名称: 通义千问 Plus
    base_url        TEXT,                               -- API 基础地址（OpenAI 兼容时必填）
    description     TEXT,                               -- 模板描述
    sort_order      INTEGER       NOT NULL DEFAULT 0,   -- 排序权重
    is_enabled      INTEGER       NOT NULL DEFAULT 1,   -- 是否启用: 0-禁用, 1-启用
    created_at      TEXT          NOT NULL DEFAULT (datetime('now'))
);

-- 唯一约束：提供商 + 模型名称
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model ON llm_model_template (provider, model_name) WHERE is_enabled = 1;

-- 初始化预置模板数据
INSERT INTO llm_model_template (provider, model_name, display_name, base_url, description, sort_order) VALUES
    ('dashscope', 'qwen-turbo', '通义千问 Turbo', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '快速响应，适合简单查询', 1),
    ('dashscope', 'qwen-plus', '通义千问 Plus', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '平衡性能和质量，推荐日常使用', 2),
    ('dashscope', 'qwen-max', '通义千问 Max', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '高质量，适合复杂分析', 3),
    ('deepseek', 'deepseek-chat', 'DeepSeek V3', 'https://api.deepseek.com/v1', '高性价比，支持多种场景', 4),
    ('openai', NULL, 'OpenAI 兼容（自定义）', NULL, '支持 SiliconFlow、Ollama 等兼容 OpenAI 格式的 API', 5);

-- -----------------------------------------------------------
-- 2. 用户模型配置表
--    用户自行配置的模型实例，关联预置模板
--    每个用户的配置彼此隔离
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_model_config (
    id                  INTEGER       PRIMARY KEY AUTOINCREMENT,
    name                TEXT          NOT NULL,             -- 用户自定义配置名称
    template_id         INTEGER       NOT NULL,             -- 关联的预置模板 ID
    provider            TEXT          NOT NULL,             -- 提供商（从模板冗余，便于查询）
    base_url            TEXT,                               -- API 基础地址（从模板冗余）
    api_key             TEXT          NOT NULL,             -- API Key（AES 加密存储）
    model_name          TEXT          NOT NULL,             -- 模型名称（从模板冗余）
    temperature         REAL          NOT NULL DEFAULT 0.1, -- 温度参数 (0~1)
    is_default          INTEGER       NOT NULL DEFAULT 0,   -- 是否默认: 0-否, 1-是
    description         TEXT,                               -- 用户备注
    created_at          TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT          NOT NULL DEFAULT (datetime('now')),
    is_deleted          INTEGER       NOT NULL DEFAULT 0    -- 逻辑删除: 0-正常, 1-已删除
);

-- 唯一约束：用户配置名称（当前用户维度）
-- 注：MVP 阶段未实现用户系统，user_id 预留为空字符串
CREATE UNIQUE INDEX IF NOT EXISTS uk_name ON llm_model_config (name) WHERE is_deleted = 0;

-- 索引：查询默认模型
CREATE INDEX IF NOT EXISTS idx_is_default ON llm_model_config (is_default) WHERE is_deleted = 0 AND is_default = 1;

-- 索引：按模板查询
CREATE INDEX IF NOT EXISTS idx_template_id ON llm_model_config (template_id) WHERE is_deleted = 0;


-- ===========================================================
-- 第二部分：会话管理相关表
-- ===========================================================

-- -----------------------------------------------------------
-- 1. Agent 会话表
--    管理会话的元数据和生命周期
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session (
    id                TEXT          PRIMARY KEY,    -- 会话ID: session-{uuid}
    title             TEXT          NOT NULL DEFAULT '新对话',  -- 会话标题
    datasource_id     INTEGER       NOT NULL,       -- 关联的数据源ID
    model_config_id   INTEGER       NOT NULL,       -- 关联的模型配置ID
    status            TEXT          NOT NULL DEFAULT 'active',  -- 状态: active/closed/expired
    message_count     INTEGER       NOT NULL DEFAULT 0,         -- 对话轮次计数
    last_message_at   TEXT,                         -- 最后一条消息时间 ISO-8601
    created_at        TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT          NOT NULL DEFAULT (datetime('now')),
    closed_at         TEXT,                         -- 关闭时间
    is_deleted        INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_session_status ON agent_session(status) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_datasource ON agent_session(datasource_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_last_msg ON agent_session(last_message_at) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 2. 会话消息历史表
--    持久化完整的对话历史（用户消息 + Agent回复 + 工具调用）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_msg (
    id                INTEGER       PRIMARY KEY AUTOINCREMENT,
    session_id        TEXT          NOT NULL,       -- 关联的会话ID
    role              TEXT          NOT NULL,       -- 角色: user/assistant/system/tool
    content           TEXT          NOT NULL,       -- 消息内容
    tool_calls        TEXT,                         -- 工具调用记录 (JSON格式，仅assistant)
    tool_result       TEXT,                         -- 工具执行结果 (JSON格式，仅tool)
    metadata          TEXT,                         -- 扩展元数据 (JSON: 耗时、token数等)
    created_at        TEXT          NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES agent_session(id)
);

CREATE INDEX IF NOT EXISTS idx_msg_session ON conversation_msg(session_id);
CREATE INDEX IF NOT EXISTS idx_msg_session_order ON conversation_msg(session_id, id);

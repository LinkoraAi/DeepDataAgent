-- -----------------------------------------------------------
-- Flyway 基线迁移 v1：数据源元数据建模
-- 表：datasource_connection / database_schema / table_info /
--      column_info / api_schema / api_field /
--      agent_session / execution_round / run_trace / chat_event（运行时 BC，来自 V1.0__init_schema.sql）
-- 约定：V{n}__{描述}.sql 一经应用即不可修改（checksum 校验），
--       后续任何变更请新增 V{n+1}__{描述}.sql。
-- 说明：全库基础字段统一为 created_at / updated_at / created_by / updated_by / is_deleted，
--       时间类型统一为 TIMESTAMPTZ（默认 now()）；时区统一约定为中国时区 Asia/Shanghai
--       （在 docker 部署时通过 TZ 环境变量约定，不在迁移脚本中强制）；
--       基础字段由 MyBatis-Plus 自动填充（INSERT/UPDATE），业务代码不手工维护；
--       数据源元数据表（1-6）列注释由领域实体与文档维护，不在迁移脚本中重复维护；
--       运行时 BC 表（7）保留 COMMENT ON。
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 1. 数据源连接表
-- -----------------------------------------------------------
CREATE TABLE datasource_connection (
    id                     BIGSERIAL    PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    type                   VARCHAR(30)  NOT NULL,
    sub_type               VARCHAR(50),
    status                 VARCHAR(20)  NOT NULL DEFAULT '',
    jdbc_connection_config JSONB,
    description            VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             VARCHAR(100),
    updated_by             VARCHAR(100),
    is_deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_name ON datasource_connection (name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 2. 数据库Schema表
-- -----------------------------------------------------------
CREATE TABLE database_schema (
    id            BIGSERIAL     PRIMARY KEY,
    connection_id BIGINT        NOT NULL,
    schema_name   VARCHAR(100)  NOT NULL,
    description   VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_connection_schema ON database_schema (connection_id, schema_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 3. 表信息表
-- -----------------------------------------------------------
CREATE TABLE table_info (
    id                   BIGSERIAL     PRIMARY KEY,
    database_schema_id   BIGINT        NOT NULL,
    table_name           VARCHAR(100)  NOT NULL,
    table_comment        VARCHAR(500),
    table_custom_comment VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_database_schema_table ON table_info (database_schema_id, table_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 4. 列信息表
-- -----------------------------------------------------------
CREATE TABLE column_info (
    id                    BIGSERIAL     PRIMARY KEY,
    table_id              BIGINT        NOT NULL,
    column_name           VARCHAR(100)  NOT NULL,
    data_type             VARCHAR(50)   NOT NULL,
    column_comment        VARCHAR(500),
    column_custom_comment VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_table_column ON column_info (table_id, column_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 5. API Schema表（config 为 JSONB）
-- -----------------------------------------------------------
CREATE TABLE api_schema (
    id            BIGSERIAL     PRIMARY KEY,
    connection_id BIGINT        NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    url           VARCHAR(1000) NOT NULL,
    method        VARCHAR(10)   NOT NULL DEFAULT 'GET',
    config        JSONB,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_connection_api ON api_schema (connection_id, url, method) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 6. API字段表
-- -----------------------------------------------------------
CREATE TABLE api_field (
    id            BIGSERIAL     PRIMARY KEY,
    api_schema_id BIGINT        NOT NULL,
    original_name VARCHAR(100)  NOT NULL,
    display_name  VARCHAR(100),
    json_path     VARCHAR(500),
    field_type    VARCHAR(50)   NOT NULL DEFAULT '',
    description   VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_api_schema_field ON api_field (api_schema_id, original_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 7. 运行时 BC：Agent 会话 + 执行轮次 + 追踪事件
-- 来源：V1.0__init_schema.sql（AgentSmith 平台运行时结构，
--       agentscope 遗留表已按 V1.0.3 移除，由框架托管）
-- 说明：字段命名已统一为全库基础字段规范（created_at/updated_at/created_by/updated_by/is_deleted），
--       与数据源元数据表保持一致，时间列使用标准 TIMESTAMPTZ 类型，不再保留独立乐观锁版本号
-- -----------------------------------------------------------

-- Agent 会话表
CREATE TABLE agent_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    agent_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    metadata JSONB NOT NULL DEFAULT '{}',
    sandbox_id VARCHAR(64),
    title VARCHAR(255),
    last_active_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  agent_session                  IS 'Agent会话表';
COMMENT ON COLUMN agent_session.id                IS '主键ID';
COMMENT ON COLUMN agent_session.session_id        IS '会话唯一标识';
COMMENT ON COLUMN agent_session.tenant_id         IS '租户ID';
COMMENT ON COLUMN agent_session.user_id           IS '用户ID';
COMMENT ON COLUMN agent_session.agent_id          IS 'Agent业务ID';
COMMENT ON COLUMN agent_session.agent_version     IS 'Agent配置版本号';
COMMENT ON COLUMN agent_session.status            IS '状态: IDLE/RUNNING/TERMINATED';
COMMENT ON COLUMN agent_session.metadata          IS '扩展元数据(JSONB)，支持 containment 查询（如 external_user_id 隔离）';
COMMENT ON COLUMN agent_session.sandbox_id        IS '沙箱实例ID';
COMMENT ON COLUMN agent_session.title             IS '会话标题';
COMMENT ON COLUMN agent_session.last_active_at    IS '最后活跃时间';
COMMENT ON COLUMN agent_session.created_at        IS '创建时间';
COMMENT ON COLUMN agent_session.updated_at        IS '更新时间';
COMMENT ON COLUMN agent_session.created_by        IS '创建人';
COMMENT ON COLUMN agent_session.updated_by        IS '更新人';
COMMENT ON COLUMN agent_session.is_deleted        IS '删除标记(0=未删除,1=已删除)';

CREATE INDEX idx_agent_session_tenant_id ON agent_session(tenant_id);
CREATE INDEX idx_agent_session_user_id ON agent_session(user_id);
CREATE INDEX idx_agent_session_agent_id ON agent_session(agent_id);
CREATE INDEX idx_agent_session_last_active_at ON agent_session(last_active_at DESC);
-- GIN 索引支持 metadata JSONB containment 查询（如 external_user_id 隔离）
CREATE INDEX idx_session_metadata ON agent_session USING GIN (metadata);

-- 执行轮次表
CREATE TABLE execution_round (
    id BIGSERIAL PRIMARY KEY,
    round_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    tenant_id VARCHAR(64) NOT NULL,
    round_number INTEGER NOT NULL,
    input TEXT NOT NULL,
    output TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    replayed_from_round_id VARCHAR(64),
    is_deleted SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  execution_round                     IS '执行轮次表';
COMMENT ON COLUMN execution_round.id                   IS '主键ID';
COMMENT ON COLUMN execution_round.round_id             IS '轮次唯一标识';
COMMENT ON COLUMN execution_round.session_id           IS '所属会话ID';
COMMENT ON COLUMN execution_round.run_id               IS '关联OpenAPI层runId';
COMMENT ON COLUMN execution_round.tenant_id            IS '租户ID（多租户隔离）';
COMMENT ON COLUMN execution_round.round_number         IS '轮次序号';
COMMENT ON COLUMN execution_round.input                IS '输入内容';
COMMENT ON COLUMN execution_round.output               IS '输出内容';
COMMENT ON COLUMN execution_round.status               IS '状态: RUNNING/COMPLETED/FAILED/INTERRUPTED';
COMMENT ON COLUMN execution_round.created_at           IS '创建时间';
COMMENT ON COLUMN execution_round.updated_at           IS '更新时间';
COMMENT ON COLUMN execution_round.created_by           IS '创建人';
COMMENT ON COLUMN execution_round.updated_by           IS '更新人';
COMMENT ON COLUMN execution_round.replayed_from_round_id IS '重放来源轮次ID（UC-RUN-005）';
COMMENT ON COLUMN execution_round.is_deleted           IS '删除标记(0=未删除,1=已删除)';

CREATE INDEX idx_execution_round_session_id ON execution_round(session_id);
CREATE INDEX idx_execution_round_tenant_id ON execution_round(tenant_id);
CREATE INDEX idx_execution_round_run_id ON execution_round(run_id);

-- 链路追踪表（OTel Span 模型）
CREATE TABLE run_trace (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64),
    round_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    span_name VARCHAR(128) NOT NULL,
    span_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OK',
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    model_name VARCHAR(128),
    estimated_cost DECIMAL(12,6),
    tool_name VARCHAR(128),
    tool_input TEXT,
    tool_output TEXT,
    attributes JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  run_trace                      IS '链路追踪表（OTel Span模型）';
COMMENT ON COLUMN run_trace.id                   IS '主键ID';
COMMENT ON COLUMN run_trace.trace_id             IS '追踪ID（同一轮次共享）';
COMMENT ON COLUMN run_trace.span_id              IS 'Span唯一标识';
COMMENT ON COLUMN run_trace.parent_span_id       IS '父Span ID（树形结构）';
COMMENT ON COLUMN run_trace.round_id             IS '所属轮次ID';
COMMENT ON COLUMN run_trace.tenant_id            IS '租户ID（多租户隔离）';
COMMENT ON COLUMN run_trace.span_name            IS 'Span名称（agent.run/llm.call/tool.call/sandbox.exec）';
COMMENT ON COLUMN run_trace.span_kind            IS 'Span类型（INTERNAL/CLIENT/SERVER）';
COMMENT ON COLUMN run_trace.status               IS '状态: OK/ERROR';
COMMENT ON COLUMN run_trace.start_time           IS '开始时间';
COMMENT ON COLUMN run_trace.end_time             IS '结束时间';
COMMENT ON COLUMN run_trace.duration_ms          IS '耗时（毫秒）';
COMMENT ON COLUMN run_trace.input_tokens         IS '输入Token数（仅llm.call）';
COMMENT ON COLUMN run_trace.output_tokens        IS '输出Token数（仅llm.call）';
COMMENT ON COLUMN run_trace.model_name           IS '模型名称（仅llm.call）';
COMMENT ON COLUMN run_trace.estimated_cost       IS '预估费用（仅llm.call）';
COMMENT ON COLUMN run_trace.tool_name            IS '工具名称（仅tool.call）';
COMMENT ON COLUMN run_trace.tool_input           IS '工具输入（仅tool.call）';
COMMENT ON COLUMN run_trace.tool_output          IS '工具输出（仅tool.call）';
COMMENT ON COLUMN run_trace.attributes           IS '扩展属性(JSON)';
COMMENT ON COLUMN run_trace.created_at           IS '创建时间';
COMMENT ON COLUMN run_trace.updated_at           IS '更新时间';
COMMENT ON COLUMN run_trace.created_by           IS '创建人';
COMMENT ON COLUMN run_trace.updated_by           IS '更新人';
COMMENT ON COLUMN run_trace.is_deleted           IS '删除标记(0=未删除,1=已删除)';

CREATE INDEX idx_run_trace_trace_id ON run_trace(trace_id);
CREATE INDEX idx_run_trace_round_id ON run_trace(round_id);
CREATE INDEX idx_run_trace_tenant_id ON run_trace(tenant_id);
CREATE INDEX idx_run_trace_span_id ON run_trace(span_id);

-- 聊天事件表（事件流存储，用于 SSE 回放和实时推送）
CREATE TABLE chat_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    round_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB,
    sequence_num BIGINT          NOT NULL,
    created_at TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ       NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE  chat_event                 IS '聊天事件流存储，支持 SSE 回放和实时推送';
COMMENT ON COLUMN chat_event.id              IS '主键ID';
COMMENT ON COLUMN chat_event.event_id        IS '事件唯一 ID';
COMMENT ON COLUMN chat_event.session_id      IS '所属会话ID';
COMMENT ON COLUMN chat_event.round_id        IS '所属轮次ID';
COMMENT ON COLUMN chat_event.tenant_id       IS '租户ID';
COMMENT ON COLUMN chat_event.event_type      IS '事件类型';
COMMENT ON COLUMN chat_event.payload         IS '事件数据（JSONB）';
COMMENT ON COLUMN chat_event.sequence_num    IS '会话内递增序列号';
COMMENT ON COLUMN chat_event.created_at      IS '创建时间';
COMMENT ON COLUMN chat_event.updated_at      IS '更新时间';
COMMENT ON COLUMN chat_event.created_by      IS '创建人';
COMMENT ON COLUMN chat_event.updated_by      IS '更新人';
COMMENT ON COLUMN chat_event.is_deleted      IS '删除标记(0=未删除,1=已删除)';

-- 按 session + sequence 查询（回放用）
CREATE INDEX idx_chat_event_session_seq ON chat_event (session_id, sequence_num);
-- 按 round 查询（单轮回放用）
CREATE INDEX idx_chat_event_round ON chat_event (round_id);
-- 按 tenant 过滤（多租户隔离）
CREATE INDEX idx_chat_event_tenant ON chat_event (tenant_id);
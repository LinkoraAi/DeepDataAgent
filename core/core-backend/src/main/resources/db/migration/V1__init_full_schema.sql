-- =====================================================================
-- Flyway 基线迁移 V1：全库 Schema（最终态）
-- ---------------------------------------------------------------
-- 说明：本脚本为全库一次性初始化基线（历史 V1~V19 演进 + 后续 environment 契约
--       config 结构、资源 ID 前缀、session_thread、技能类型词汇等增量的最终态合并），
--       一经应用即不可修改（checksum 校验），后续任何改动请新增 V{n+1}__{desc}.sql。
--       基线例外（move-coordination-leases-to-redis PR-C4）：已删除 coordination BC 的
--       协调租约表建表段（协调租约全量迁至 Redis，PG 侧表零代码读写），故本脚本 checksum
--       变化——存量开发/集成库须 `flyway repair` 对齐校验和（或直接重建空库）后再迁移；
--       V2 与协调租约无关，保持原样不动。
-- 分组：按限界上下文（BC）划分 —— auth / file / datasource / agent / skill /
--       memory / vault / runtime。
-- 约定：全库基础字段统一 created_at / updated_at / created_by / updated_by / is_deleted，
--       时间类型统一 TIMESTAMPTZ（默认 now()），时区统一 Asia/Shanghai；
--       基础字段由 MyBatis-Plus 自动填充；数据源元数据与业务表列注释由领域实体维护，
--       此处仅对运行簿 / 关键演进列保留注释。
-- =====================================================================

-- =====================================================================
-- BC: auth（认证）—— users
-- =====================================================================
-- id BIGSERIAL 即「数字 user_id」，JWT sub 载荷为其字符串形式；
-- password_hash 存 bcrypt 散列，明文/散列均不得出现在任何响应与日志。
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_users_email ON users (email) WHERE is_deleted = 0;

-- =====================================================================
-- BC: file（文件）
-- =====================================================================
-- file_id 业务ID（前缀 file_）；文件一等资源形态：内容不入库，
-- 落磁盘目录 <dataRoot>/files/<file_id>，表内仅元数据 + content_sha256 一致性摘要；
-- mime_type 服务端探测；purpose 五态必填；downloadable 由 purpose 派生；
-- scope / metadata 为 JSONB（scope 未关联为 NULL，metadata 缺省 {}）。
CREATE TABLE files (
    id              BIGSERIAL     PRIMARY KEY,
    file_id         VARCHAR(64)   NOT NULL,
    owner_id        BIGINT        NOT NULL,
    filename        VARCHAR(255)  NOT NULL,
    mime_type       VARCHAR(128)  NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    purpose         VARCHAR(32)   NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ready',
    downloadable    BOOLEAN       NOT NULL DEFAULT false,
    scope           JSONB,
    metadata        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    content_sha256  VARCHAR(64)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    is_deleted      SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_files_file_id ON files (file_id) WHERE is_deleted = 0;
-- 列表主查询路径：owner 隔离 + (created_at, id) 升序游标分页
CREATE INDEX idx_files_owner_created ON files (owner_id, created_at, id) WHERE is_deleted = 0;

-- =====================================================================
-- BC: datasource（数据源元数据）
-- =====================================================================
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

-- =====================================================================
-- BC: agent（Agent 定义 / 版本 / 模型配置 / 运行环境 / 调度器）
-- =====================================================================
CREATE TABLE agent_definition (
    id             BIGSERIAL    PRIMARY KEY,
    agent_id       VARCHAR(64)  NOT NULL,
    name           VARCHAR(256) NOT NULL,
    description    VARCHAR(2048),
    archived_at    TIMESTAMPTZ,
    latest_version INTEGER      NOT NULL DEFAULT 0,
    active_version INTEGER      NOT NULL DEFAULT 0,
    owner_id       BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_agent_definition_agent_id ON agent_definition (agent_id) WHERE is_deleted = 0;
-- 名称不做 owner 内唯一约束（公开契约仅要求 1-256 长度校验）

COMMENT ON COLUMN agent_definition.active_version IS '当前生效版本号（默认随发布同步 latest_version，可回滚）';
COMMENT ON COLUMN agent_definition.archived_at     IS '归档时间（NULL=未归档；对外仅以 archived_at 表达，不输出 archived 布尔）';

-- 每次发布 = 一行快照，version_number 为发布号；内联能力配方快照 + 引用接线。
CREATE TABLE agent_version (
    id                  BIGSERIAL    PRIMARY KEY,
    version_id          VARCHAR(64)  NOT NULL,
    agent_id            VARCHAR(64)  NOT NULL,
    version_number      INTEGER      NOT NULL,
    name                VARCHAR(256) NOT NULL,
    description         VARCHAR(2048),
    system_prompt       TEXT         NOT NULL DEFAULT '',
    model_profile_id    VARCHAR(64),
    model_json          JSONB,
    skills_json         JSONB,
    tools_json          JSONB,
    mcp_servers_json    JSONB,
    multiagent          JSONB,
    metadata_json       JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    is_deleted          SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_agent_version_num ON agent_version (agent_id, version_number) WHERE is_deleted = 0;
CREATE INDEX idx_agent_version_agent_id  ON agent_version (agent_id);

COMMENT ON COLUMN agent_version.system_prompt       IS '系统提示词(对外字段名 system；版本快照唯一指令载体)';
COMMENT ON COLUMN agent_version.model_profile_id    IS '内部模型供应商配置引用(可空，由模型目录映射解析写入；不进入对外契约)';
COMMENT ON COLUMN agent_version.model_json          IS '模型引用(JSONB：目录模型 id 字符串简写或 {"id","effort","context_window"} 对象，按提交形态回显)';
COMMENT ON COLUMN agent_version.skills_json         IS '技能绑定数组(JSONB:[{"type":"catalog|custom","skill_id":"skill_...","version":"epoch微秒"}]，省略/latest 为动态版)';
COMMENT ON COLUMN agent_version.metadata_json       IS '业务自定义元数据(JSONB 键值对象，可空)';
COMMENT ON COLUMN agent_version.tools_json          IS '内联工具配方(JSONB:[{"name":...}])';
COMMENT ON COLUMN agent_version.mcp_servers_json    IS '内联外部MCP工具源配方(JSONB)';
COMMENT ON COLUMN agent_version.multiagent          IS '多智能体编排配置(JSONB；本期仅持久化)';

-- 模型配置：凭证经 APP_MODEL_ENCRYPTION_KEY 独立密钥 AES/GCM 加密，仅内嵌加密单一模式。
CREATE TABLE model_profile (
    id                     BIGSERIAL    PRIMARY KEY,
    profile_id             VARCHAR(64)  NOT NULL,
    display_name           VARCHAR(32)  NOT NULL,
    description            VARCHAR(500),
    api_format             VARCHAR(32)  NOT NULL,
    api_endpoint_url       VARCHAR(512) NOT NULL,
    model_name             VARCHAR(128) NOT NULL,
    encrypted_credential   TEXT         NOT NULL DEFAULT '',
    model_series           VARCHAR(64),
    context_window_input   INTEGER,
    context_window_output  INTEGER,
    tool_call_rounds       INTEGER      NOT NULL DEFAULT 999999,
    model_type             INTEGER      NOT NULL DEFAULT 1,
    vector_dimension       INTEGER,
    status                 VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    owner_id               BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             VARCHAR(100),
    updated_by             VARCHAR(100),
    is_deleted             SMALLINT     NOT NULL DEFAULT 0
);

-- display_name 保持全局唯一，不随 owner 收敛
CREATE UNIQUE INDEX uk_model_profile      ON model_profile (profile_id)   WHERE is_deleted = 0;
CREATE UNIQUE INDEX uk_model_profile_name ON model_profile (display_name) WHERE is_deleted = 0;

-- 运行环境：沙箱规格 JSONB；name 按 owner 唯一；config.type 承载环境类型（无冗余 type 列）。
CREATE TABLE environment (
    id             BIGSERIAL     PRIMARY KEY,
    environment_id VARCHAR(64)   NOT NULL,
    name           VARCHAR(256)  NOT NULL,
    description    VARCHAR(2048),
    config         JSONB         NOT NULL DEFAULT '{"type":"cloud"}'::jsonb,
    metadata       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    owner_id       BIGINT        NOT NULL DEFAULT 0,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_environment_environment_id ON environment (environment_id) WHERE is_deleted = 0;
CREATE UNIQUE INDEX uk_environment_owner_name ON environment (owner_id, name) WHERE is_deleted = 0;
CREATE INDEX idx_environment_owner_id ON environment (owner_id);

COMMENT ON COLUMN environment.description IS '环境描述(≤2048，可空)';
COMMENT ON COLUMN environment.archived_at IS '归档时间（NULL=未归档；归档后不可被新 Session 引用）';
COMMENT ON COLUMN environment.config      IS '环境配置(JSONB：{"type":"cloud|self_hosted","packages":{"apt":[],"pip":[],"npm":[]},"setup_script":...})';

-- 调度器（Deployment 即调度器资源：schedule(cron+timezone) 或手动/webhook 触发，触发即新建 Session 跑 turn 并产生 Run 记录）。
-- 版本激活/回滚职责归属 agent_definition.active_version，本表不承担。
CREATE TABLE deployment (
    id                    BIGSERIAL     PRIMARY KEY,
    deployment_id         VARCHAR(64)   NOT NULL,
    agent_id              VARCHAR(64)   NOT NULL,
    owner_id              BIGINT        NOT NULL DEFAULT 0,
    name                  VARCHAR(64)   NOT NULL DEFAULT 'scheduler',
    description           VARCHAR(500),
    agent_version         INTEGER       NOT NULL,
    environment_id        VARCHAR(64),
    environment_variables JSONB         NOT NULL DEFAULT '{}',
    resources             JSONB         NOT NULL DEFAULT '[]',
    vault_ids             JSONB         NOT NULL DEFAULT '[]',
    initial_events        JSONB         NOT NULL DEFAULT '[]',
    metadata              JSONB         NOT NULL DEFAULT '{}',
    schedule              JSONB,
    next_run_at           TIMESTAMPTZ,
    webhook_token         VARCHAR(128),
    status                VARCHAR(16)   NOT NULL DEFAULT 'active',
    paused_reason         VARCHAR(512),
    last_run_at           TIMESTAMPTZ,
    last_session_id       VARCHAR(64),
    last_status           VARCHAR(32),
    archived_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    is_deleted            SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_deployment_deployment_id ON deployment (deployment_id) WHERE is_deleted = 0;
CREATE INDEX idx_deployment_agent_id ON deployment (agent_id);
CREATE INDEX idx_deployment_owner_active ON deployment (owner_id) WHERE is_deleted = 0 AND archived_at IS NULL;
-- 调度轮询领取索引：仅覆盖「有 schedule、active、未归档」的到期候选行
CREATE INDEX idx_deployment_due ON deployment (next_run_at)
    WHERE is_deleted = 0 AND archived_at IS NULL AND status = 'active' AND schedule IS NOT NULL;
-- GIN 索引支持 metadata JSONB containment 过滤
CREATE INDEX idx_deployment_metadata ON deployment USING GIN (metadata);

COMMENT ON TABLE  deployment                      IS '调度器表（Deployment 即调度器资源，不承担版本激活/回滚职责）';
COMMENT ON COLUMN deployment.agent_version        IS '创建时解析并固定的Agent版本号（省略版本时服务端取active_version固化，触发不随后续发布漂移）';
COMMENT ON COLUMN deployment.description          IS '调度器描述';
COMMENT ON COLUMN deployment.environment_id       IS '指向的Environment业务ID（null=回退默认环境）';
COMMENT ON COLUMN deployment.environment_variables IS '触发会话注入的环境变量（JSONB 对象，触发时透传 runtime 装配）';
COMMENT ON COLUMN deployment.resources            IS '触发会话挂载资源（jsonb 数组，格式对齐 session resources，触发时透传 runtime）';
COMMENT ON COLUMN deployment.vault_ids            IS '触发会话挂载保管库业务ID列表（JSONB 数组）';
COMMENT ON COLUMN deployment.initial_events       IS '首批注入的用户消息事件（JSONB 数组 [{type:user_message,text}]，触发时合成首个 turn 消息）';
COMMENT ON COLUMN deployment.metadata             IS '扩展元数据（JSONB 对象，触发会话透传）';
COMMENT ON COLUMN deployment.schedule             IS '调度配置（JSONB {cron,timezone}；null=仅手动/webhook触发）';
COMMENT ON COLUMN deployment.next_run_at          IS '下次到期触发时间（schedule 非空时物化，调度轮询领取依据）';
COMMENT ON COLUMN deployment.webhook_token        IS 'webhook触发密钥（null=未开放webhook；路径token免JWT回调）';
COMMENT ON COLUMN deployment.status               IS '状态（active/paused，替代旧 enabled 布尔）';
COMMENT ON COLUMN deployment.paused_reason        IS '暂停原因（status=paused 时有值）';
COMMENT ON COLUMN deployment.last_run_at          IS '最近一次触发时间';
COMMENT ON COLUMN deployment.last_session_id      IS '最近一次触发新建的会话ID';
COMMENT ON COLUMN deployment.last_status          IS '最近一次触发执行结果状态';
COMMENT ON COLUMN deployment.archived_at          IS '归档时间（归档=archived_at + status=paused 双写）';

-- 调度器运行记录（每次触发产生一行；结果状态本期为初始 running，终态回写随运行查询面/事件计量接入）。
CREATE TABLE deployment_run (
    id            BIGSERIAL   PRIMARY KEY,
    run_id        VARCHAR(64) NOT NULL,
    deployment_id VARCHAR(64) NOT NULL,
    session_id    VARCHAR(64),
    trigger_kind  VARCHAR(16) NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'running',
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT    NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_deployment_run_run_id ON deployment_run (run_id) WHERE is_deleted = 0;
CREATE INDEX idx_deployment_run_deployment ON deployment_run (deployment_id, started_at DESC);
CREATE INDEX idx_deployment_run_session_id ON deployment_run (session_id);

COMMENT ON TABLE  deployment_run            IS '调度器运行记录表（触发时间/结果状态/关联会话）';
COMMENT ON COLUMN deployment_run.run_id     IS '运行记录业务ID（drun_ 前缀）';
COMMENT ON COLUMN deployment_run.session_id IS '本次触发新建的会话ID（启动失败时为 null）';
COMMENT ON COLUMN deployment_run.trigger_kind IS '触发方式（cron/manual/webhook，源码小写）';
COMMENT ON COLUMN deployment_run.status     IS '运行状态（running/succeeded/failed/terminated；本期触发落 running）';
COMMENT ON COLUMN deployment_run.started_at IS '触发时间';
COMMENT ON COLUMN deployment_run.finished_at IS '结束时间（终态回写随后续任务接通）';

-- =====================================================================
-- BC: skill（技能资产原语：Skill 聚合 + 不可变内容版本）
-- ---------------------------------------------------------------
-- 技能为文件包资产，壳对象按 skill_id 寻址、内容按 skill_id + epoch 版本寻址。
-- skill_id 业务ID（前缀 skill_）；source 取 catalog（平台目录）/ custom（本系统自建）；
-- display_title 创建后不可改；latest_version 指向最新版本（全部版本删除后为 NULL）；
-- 删除为逻辑删除（is_deleted），历史版本数据保留。
CREATE TABLE skill (
    id            BIGSERIAL    PRIMARY KEY,
    skill_id      VARCHAR(64)  NOT NULL,
    display_title VARCHAR(255) NOT NULL,
    source        VARCHAR(16)  NOT NULL,
    latest_version VARCHAR(32),
    metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    owner_id      BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_skill_skill_id ON skill (skill_id) WHERE is_deleted = 0;
CREATE INDEX idx_skill_owner_id ON skill (owner_id);

COMMENT ON TABLE  skill                IS '技能壳表(Skill 聚合根，展示名 + 来源 + 最新版本指针)';
COMMENT ON COLUMN skill.skill_id       IS '技能业务ID(前缀 skill_)';
COMMENT ON COLUMN skill.display_title  IS '技能展示名(≤255，创建后不可改)';
COMMENT ON COLUMN skill.source         IS '技能来源(catalog=平台目录 / custom=本系统自建)';
COMMENT ON COLUMN skill.latest_version IS '最新版本键(创建时刻 epoch 微秒字符串；全部版本删除后为 NULL)';
COMMENT ON COLUMN skill.metadata       IS '业务自定义元数据(JSONB 键值对象，缺省空对象)';
COMMENT ON COLUMN skill.owner_id       IS '归属用户ID';

-- 技能内容版本(不可变快照)：包文件树落磁盘资产目录 <skill-asset-root>/<skill_id>/<epoch>/，
-- 本表仅存 frontmatter 派生元数据与内部完整性校验值 / 大小。
CREATE TABLE skill_content_version (
    id             BIGSERIAL    PRIMARY KEY,
    version_id     VARCHAR(64)  NOT NULL,
    skill_id       VARCHAR(64)  NOT NULL,
    version        VARCHAR(32)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    description    VARCHAR(5120) NOT NULL DEFAULT '',
    directory      VARCHAR(64)  NOT NULL,
    content_sha256 VARCHAR(64)  NOT NULL,
    content_size   BIGINT       NOT NULL,
    resources      JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_skill_content_version ON skill_content_version (skill_id, version) WHERE is_deleted = 0;
CREATE UNIQUE INDEX uk_skill_content_version_id ON skill_content_version (version_id) WHERE is_deleted = 0;
CREATE INDEX idx_skill_content_version_skill_id ON skill_content_version (skill_id);

COMMENT ON TABLE  skill_content_version                IS '技能内容版本表(不可变快照，包内容落磁盘，本表存 frontmatter 元数据与完整性校验值)';
COMMENT ON COLUMN skill_content_version.version_id     IS '版本业务ID(前缀 skillver_)';
COMMENT ON COLUMN skill_content_version.skill_id       IS '所属技能业务ID';
COMMENT ON COLUMN skill_content_version.version        IS '版本键(创建时刻 epoch 微秒字符串，不可变；字典序与时间序一致)';
COMMENT ON COLUMN skill_content_version.name           IS 'frontmatter name(≤64，[a-z0-9][a-z0-9_-]*，跨版本一致)';
COMMENT ON COLUMN skill_content_version.description    IS 'frontmatter description(≤5120)';
COMMENT ON COLUMN skill_content_version.directory      IS '包顶级目录名(恒等于 name)';
COMMENT ON COLUMN skill_content_version.content_sha256 IS '包内容 SHA-256 校验值(hex，64 字符，内部完整性手段，不进入响应)';
COMMENT ON COLUMN skill_content_version.content_size   IS '包内容字节长度(解压后总量，上限 50MB)';
COMMENT ON COLUMN skill_content_version.resources      IS '资源文件相对路径→内容摘要清单(JSONB，可空)';

-- 幂等记录：POST /agents 携带 Idempotency-Key 时按用户隔离保存首次创建结果。
CREATE TABLE idempotency_record (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    owner_id        BIGINT       NOT NULL,
    scope           VARCHAR(64)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    is_deleted      SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_idempotency_record_key ON idempotency_record (owner_id, scope, idempotency_key) WHERE is_deleted = 0;

COMMENT ON TABLE  idempotency_record                 IS '幂等记录表(短窗内相同键返回首次结果；按 owner 隔离)';
COMMENT ON COLUMN idempotency_record.idempotency_key IS '请求 Idempotency-Key 头原值';
COMMENT ON COLUMN idempotency_record.owner_id        IS '归属用户ID(幂等键按用户隔离)';
COMMENT ON COLUMN idempotency_record.scope           IS '作用域(如 post_agents)';
COMMENT ON COLUMN idempotency_record.request_hash    IS '首次请求体 SHA-256(hex，用于同键异体冲突判定)';
COMMENT ON COLUMN idempotency_record.response_status IS '首次响应 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.response_body   IS '首次响应体(JSON 字符串)';

-- =====================================================================
-- BC: memory（记忆库 Store → Memory(entry) → MemoryVersion）
-- =====================================================================
-- 对齐 Memory Store 原语：Store 带 status 与统计列；Memory 以 mem_ 前缀业务ID、
-- version 做 OCC 乐观并发（content ≤ 100KB）；版本历史 memver_ 前缀不可变快照 + 版本级 redact。
CREATE TABLE memory_stores (
    id            BIGSERIAL     PRIMARY KEY,
    store_id      VARCHAR(64)   NOT NULL,
    name          VARCHAR(64)   NOT NULL,
    description   VARCHAR(500)  NOT NULL DEFAULT '',
    status        VARCHAR(20)   NOT NULL DEFAULT 'active',
    entry_count   INTEGER       NOT NULL DEFAULT 0,
    total_size    BIGINT        NOT NULL DEFAULT 0,
    owner_id      BIGINT        NOT NULL,
    archived_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_memory_stores_store_id ON memory_stores (store_id) WHERE is_deleted = 0;
CREATE INDEX idx_memory_stores_owner_id ON memory_stores (owner_id);

COMMENT ON TABLE  memory_stores                     IS '记忆库表(Store 聚合根)';
COMMENT ON COLUMN memory_stores.store_id            IS '记忆库业务ID(前缀 ms_)';
COMMENT ON COLUMN memory_stores.name                IS '记忆库名称';
COMMENT ON COLUMN memory_stores.description         IS '记忆库描述(缺省空串)';
COMMENT ON COLUMN memory_stores.status              IS '状态(active/archived，归档后只读、写操作拒绝 409)';
COMMENT ON COLUMN memory_stores.entry_count         IS '活跃记忆条目数(随内容变更维护)';
COMMENT ON COLUMN memory_stores.total_size          IS '活跃记忆内容总字节数(随内容变更维护)';
COMMENT ON COLUMN memory_stores.owner_id            IS '归属用户ID';
COMMENT ON COLUMN memory_stores.archived_at         IS '归档时间(NULL=未归档)';

CREATE TABLE memories (
    id              BIGSERIAL     PRIMARY KEY,
    memory_id       VARCHAR(64)   NOT NULL,
    store_id        VARCHAR(64)   NOT NULL,
    path            VARCHAR(512)  NOT NULL,
    version         INTEGER       NOT NULL DEFAULT 1,
    size            BIGINT        NOT NULL,
    content_sha256  VARCHAR(64)   NOT NULL,
    metadata        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_memories_memory_id ON memories (memory_id);
CREATE UNIQUE INDEX uk_memories_store_path ON memories (store_id, path) WHERE deleted_at IS NULL;
CREATE INDEX idx_memories_store_id ON memories (store_id);

COMMENT ON TABLE  memories                  IS '记忆条目表(Memory，store+path 活跃唯一，tombstone 软删)';
COMMENT ON COLUMN memories.memory_id        IS '记忆业务ID(前缀 mem_)';
COMMENT ON COLUMN memories.store_id         IS '所属记忆库业务ID';
COMMENT ON COLUMN memories.path             IS '记忆路径(相对路径，不以 / 开头，更新时不可变)';
COMMENT ON COLUMN memories.version          IS '当前版本号(OCC 乐观并发，写时 +1)';
COMMENT ON COLUMN memories.size             IS '当前内容字节长度(UTF-8，上限 100KB)';
COMMENT ON COLUMN memories.content_sha256   IS '当前内容 SHA-256 校验值(hex，64 字符)';
COMMENT ON COLUMN memories.metadata         IS '自定义元数据(JSONB 对象，≤16 对；key 1-64 字符、value ≤512 字符)';
COMMENT ON COLUMN memories.deleted_at       IS '删除时间(tombstone 软删，NULL=活跃)';

CREATE TABLE memory_versions (
    id              BIGSERIAL     PRIMARY KEY,
    version_id      VARCHAR(64)   NOT NULL,
    store_id        VARCHAR(64)   NOT NULL,
    entry_id        VARCHAR(64)   NOT NULL,
    entry_path      VARCHAR(512)  NOT NULL,
    version         INTEGER       NOT NULL,
    action          VARCHAR(20)   NOT NULL,
    content         TEXT,
    size            BIGINT,
    content_sha256  VARCHAR(64),
    redacted        BOOLEAN       NOT NULL DEFAULT FALSE,
    redacted_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_memory_versions_version_id ON memory_versions (version_id);
CREATE UNIQUE INDEX uk_memory_versions_entry_version ON memory_versions (entry_id, version);
CREATE INDEX idx_memory_versions_store_id ON memory_versions (store_id);

COMMENT ON TABLE  memory_versions                IS '记忆版本表(不可变快照，含删除 tombstone 版本)';
COMMENT ON COLUMN memory_versions.version_id     IS '版本业务ID(前缀 memver_)';
COMMENT ON COLUMN memory_versions.store_id       IS '所属记忆库业务ID';
COMMENT ON COLUMN memory_versions.entry_id       IS '所属记忆业务ID(mem_ 前缀)';
COMMENT ON COLUMN memory_versions.entry_path     IS '记忆路径快照';
COMMENT ON COLUMN memory_versions.version        IS '版本号(entry 内从 1 递增)';
COMMENT ON COLUMN memory_versions.action         IS '动作类型(created/updated/deleted)';
COMMENT ON COLUMN memory_versions.content        IS '文本内容(tombstone 版本为 NULL；redact 后置 NULL)';
COMMENT ON COLUMN memory_versions.size           IS '内容字节长度(UTF-8，tombstone 为 NULL)';
COMMENT ON COLUMN memory_versions.content_sha256 IS '内容 SHA-256 校验值(redact 后置 NULL)';
COMMENT ON COLUMN memory_versions.redacted       IS '是否已脱敏(脱敏后 content 不再返回)';
COMMENT ON COLUMN memory_versions.redacted_at    IS '脱敏时间';

-- =====================================================================
-- BC: vault（保管库 + 凭证两层模型，对齐 Managed Agents Vault 公开契约）
-- =====================================================================
-- Vault 聚合根仅以 owner_id 隔离；Credential 以「加密信封」单列承载全部密文成分
-- （token / access_token / refresh_token / client_secret 均在加密后的 JSON 信封内），
-- 明文永不落库；未加密列仅保留调度与匹配所需的非密成分（mcp_server_url / expires_at）。
-- 产品边界：Vault 为 MCP 服务器密钥托管，凭据一律以 mcp_server_url 匹配 MCP 连接，
-- 故 Target 列恒为 URL（无第二种 Target 形态）；删除无引用约束（archive 软删 / delete 级联硬删）。
CREATE TABLE vaults (
    id            BIGSERIAL     PRIMARY KEY,
    vault_id      VARCHAR(64)   NOT NULL,
    display_name  VARCHAR(255)  NOT NULL,
    metadata_json JSONB,
    owner_id      BIGINT        NOT NULL,
    archived_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_vaults_vault_id ON vaults (vault_id) WHERE is_deleted = 0;
CREATE INDEX idx_vaults_owner_id ON vaults (owner_id);

COMMENT ON COLUMN vaults.vault_id      IS '保管库业务ID(前缀 vault_)';
COMMENT ON COLUMN vaults.display_name  IS '显示名称(≤255，对齐公开契约)';
COMMENT ON COLUMN vaults.metadata_json IS '元数据(JSONB，可选)';
COMMENT ON COLUMN vaults.owner_id      IS '归属用户ID';
COMMENT ON COLUMN vaults.archived_at   IS '归档时间(软删，NULL=未归档)';

CREATE TABLE vault_credentials (
    id             BIGSERIAL     PRIMARY KEY,
    credential_id  VARCHAR(64)   NOT NULL,
    vault_id       VARCHAR(64)   NOT NULL,
    auth_type      VARCHAR(32)   NOT NULL,
    mcp_server_url VARCHAR(2048) NOT NULL,
    ciphertext     BYTEA         NOT NULL,
    key_version    INTEGER       NOT NULL DEFAULT 1,
    expires_at     TIMESTAMPTZ,
    metadata_json  JSONB,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_vault_credentials_credential_id ON vault_credentials (credential_id) WHERE is_deleted = 0;
CREATE INDEX idx_vault_credentials_vault_id ON vault_credentials (vault_id);
-- 同一 MCP server 只允许一条活跃凭据：机械落实「一个连接只选一种认证方式，避免多个凭据竞争同一目标」
-- （对齐公开契约「该 MCP server 已存在 active 凭证」的 409 语义）
CREATE UNIQUE INDEX uk_vault_credentials_url_target
    ON vault_credentials (vault_id, mcp_server_url)
    WHERE is_deleted = 0 AND archived_at IS NULL;

COMMENT ON TABLE  vault_credentials                    IS '凭证表(VaultCredential，加密信封落库、只写不读)';
COMMENT ON COLUMN vault_credentials.credential_id      IS '凭证业务唯一ID(前缀 vcred_)';
COMMENT ON COLUMN vault_credentials.vault_id           IS '所属保管库业务ID';
COMMENT ON COLUMN vault_credentials.auth_type          IS '凭证鉴权类型(static_bearer | mcp_oauth，值域由领域枚举校验)';
COMMENT ON COLUMN vault_credentials.mcp_server_url     IS '绑定的 MCP 服务器 URL(≤2048，运行时按此精确匹配 MCP 连接后注入鉴权头)';
COMMENT ON COLUMN vault_credentials.ciphertext         IS 'AES/GCM 加密后的凭据信封(BYTEA：{token|access_token|refresh|client_secret} JSON 整体加密，明文永不落库)';
COMMENT ON COLUMN vault_credentials.key_version        IS '加密密钥版本(支持 master key 读旧写新无损轮换)';
COMMENT ON COLUMN vault_credentials.expires_at         IS 'mcp_oauth access_token 过期时间(非密成分，明文列供临期刷新调度读取；其余类型为 NULL)';
COMMENT ON COLUMN vault_credentials.metadata_json      IS '凭证元数据(JSONB，与凭证一起存储，可选)';
COMMENT ON COLUMN vault_credentials.archived_at        IS '凭证归档时间(软删，NULL=未归档；归档不再用于新 Session 挂载)';

-- =====================================================================
-- BC: runtime（Agent 会话 + 事件流）
-- 轨迹/计量全部由 append-only 事件流承载。
-- =====================================================================
-- status 对外四态词汇（初始态 idle）+ turn_phase 内部执行相位双列；环境/保管库/记忆库/环境变量挂载；触发打标。
-- 归档不是状态取值：archived_at 为独立正交维度，可与任意 status 组合。
CREATE TABLE agent_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    agent_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'idle',
    turn_phase VARCHAR(32) NOT NULL DEFAULT 'idle',
    metadata JSONB NOT NULL DEFAULT '{}',
    title VARCHAR(255),
    environment_id VARCHAR(64),
    vault_ids JSONB NOT NULL DEFAULT '[]',
    memory_store_ids JSONB NOT NULL DEFAULT '[]',
    environment_variables JSONB NOT NULL DEFAULT '{}',
    resources jsonb NOT NULL DEFAULT '[]',
    last_active_at TIMESTAMPTZ,
    trigger_type VARCHAR(32),
    trigger_id VARCHAR(64),
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  agent_session                  IS 'Agent会话表';
COMMENT ON COLUMN agent_session.session_id        IS '会话唯一标识';
COMMENT ON COLUMN agent_session.user_id           IS '用户ID';
COMMENT ON COLUMN agent_session.agent_id          IS 'Agent业务ID';
COMMENT ON COLUMN agent_session.agent_version     IS 'Agent配置版本号';
COMMENT ON COLUMN agent_session.status            IS '会话状态(对外四态): idle/running/rescheduling/terminated';
COMMENT ON COLUMN agent_session.turn_phase        IS '内部执行相位(永不外显): idle/running/awaiting_confirmation/cancelling；相位非 idle 期间对外 status 恒读 running';
COMMENT ON COLUMN agent_session.metadata          IS '扩展元数据(JSONB)，支持 containment 查询（如 external_user_id 隔离）';
COMMENT ON COLUMN agent_session.title             IS '会话标题';
COMMENT ON COLUMN agent_session.environment_id    IS '会话挂载执行环境业务ID(env_ 前缀；self_hosted 环境装配时 400)';
COMMENT ON COLUMN agent_session.vault_ids         IS '会话挂载保管库业务ID列表(JSONB 数组，运行时由控制平面按 Target 匹配后注入 MCP 连接鉴权头，凭据不进沙箱数据面)';
COMMENT ON COLUMN agent_session.memory_store_ids  IS '会话挂载记忆库业务ID列表(JSONB 数组)';
COMMENT ON COLUMN agent_session.environment_variables IS '会话级环境变量(JSONB 对象，装配时注入 Harness)';
COMMENT ON COLUMN agent_session.last_active_at    IS '最后活跃时间';
COMMENT ON COLUMN agent_session.resources         IS '会话挂载资源引用（jsonb 数组，三类: file/github_repository/memory_store）';
COMMENT ON COLUMN agent_session.trigger_type      IS '触发来源类型（manual/webhook/cron，源码小写；null=普通用户会话）';
COMMENT ON COLUMN agent_session.trigger_id        IS '触发来源调度器业务ID（deployment_id；trigger_type 存在时必填）';
COMMENT ON COLUMN agent_session.archived_at       IS '归档时间（独立正交维度，不与 status 双写；是归档 + status 保持原值）';

CREATE INDEX idx_agent_session_user_id ON agent_session(user_id);
CREATE INDEX idx_agent_session_agent_id ON agent_session(agent_id);
CREATE INDEX idx_agent_session_last_active_at ON agent_session(last_active_at DESC);
-- GIN 索引支持 metadata JSONB containment 查询（如 external_user_id 隔离）
CREATE INDEX idx_session_metadata ON agent_session USING GIN (metadata);
-- 会话列表查询复合索引（agent_id + status 过滤的游标分页）
CREATE INDEX idx_agent_session_agent_status ON agent_session (agent_id, status);
-- 环境维度会话过滤索引
CREATE INDEX idx_agent_session_environment_id ON agent_session (environment_id) WHERE is_deleted = 0;
-- 触发追溯辅助索引：按触发来源调度器可反查其创建的全部会话
CREATE INDEX idx_agent_session_trigger ON agent_session (trigger_id, trigger_type) WHERE is_deleted = 0;
-- 业务 ID 全局唯一（与全库 uk_<table>_<id> 口径一致：逻辑删除行不占键）
CREATE UNIQUE INDEX uk_agent_session_session_id ON agent_session (session_id) WHERE is_deleted = 0;

-- append-only 事件流，信封闭合 id/sessionId/seq/type/payload/processedAt/createdAt。
CREATE TABLE chat_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    type VARCHAR(64) NOT NULL,
    payload JSONB,
    seq BIGINT          NOT NULL,
    processed_at TIMESTAMPTZ,
    session_thread_id VARCHAR(64),
    created_at TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ       NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE  chat_event                 IS '聊天事件流存储（append-only），信封闭合 id/sessionId/seq/type/payload/processedAt/createdAt';
COMMENT ON COLUMN chat_event.event_id        IS '事件业务 ID（evt_ 前缀，全局唯一索引幂等键）';
COMMENT ON COLUMN chat_event.session_id      IS '所属会话ID';
COMMENT ON COLUMN chat_event.type            IS '源码事件类型（{域}.{动作}，对齐 agentscope-service 权威事件表）';
COMMENT ON COLUMN chat_event.payload         IS '类型特化事件数据（JSONB）';
COMMENT ON COLUMN chat_event.seq             IS '会话内单调序列号（回放游标，唯一索引 (session_id, seq) 兜底）';
COMMENT ON COLUMN chat_event.processed_at    IS '事件处理时间';
COMMENT ON COLUMN chat_event.session_thread_id IS '事件线程归属（sthr_，可空=会话级事件；线程事件历史过滤键，写入随执行面接入）';
COMMENT ON COLUMN chat_event.created_at      IS '创建时间';

-- 按 session + seq 查询（回放用）；唯一索引充当「MAX+1 内存分配」库层兜底，
-- 杜绝并发/异常上下文 (session_id, seq) 重复，保证回放与实时流一致
CREATE UNIQUE INDEX idx_chat_event_session_seq ON chat_event (session_id, seq);
-- 线程级事件历史过滤索引（线程作用域子集 = chat_event 按 session_thread_id 过滤）
CREATE INDEX idx_chat_event_thread ON chat_event (session_id, session_thread_id, seq) WHERE session_thread_id IS NOT NULL;
-- 业务 ID 全局唯一（与全库 uk_<table>_<id> 口径一致：逻辑删除行不占键）
CREATE UNIQUE INDEX uk_chat_event_event_id ON chat_event (event_id) WHERE is_deleted = 0;

-- Session Thread（协调器多线程场景的线程记录，6.3）：
-- 会话创建时落一行「主线程」（parent_thread_id=null，协调器主线程不可归档）；
-- 子线程由执行面委派产生（本期仅查询面，执行面接入归集注记）。
CREATE TABLE session_thread (
    id BIGSERIAL PRIMARY KEY,
    thread_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    parent_thread_id VARCHAR(64),
    agent JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(32) NOT NULL DEFAULT 'idle',
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  session_thread                   IS 'Session线程表（协调器多线程）';
COMMENT ON COLUMN session_thread.thread_id         IS '线程业务 ID（sthr_ 前缀）';
COMMENT ON COLUMN session_thread.session_id        IS '所属会话ID';
COMMENT ON COLUMN session_thread.parent_thread_id  IS '父线程ID（协调器主线程为 null，主线程不可归档）';
COMMENT ON COLUMN session_thread.agent             IS '线程 Agent 快照（JSONB，Session 嵌入快照规则再去 multiagent）';
COMMENT ON COLUMN session_thread.status            IS '线程状态（对外四态小写规范值：idle/running/rescheduling/terminated）';
COMMENT ON COLUMN session_thread.archived_at       IS '归档时间（独立正交维度；归档动作同时将 status 置 terminated，不与 status 双写为 archived）';

CREATE UNIQUE INDEX uk_session_thread_thread_id ON session_thread (thread_id) WHERE is_deleted = 0;
CREATE INDEX idx_session_thread_session ON session_thread (session_id, id);

-- =====================================================================
-- BC: coordination（协调层租约）—— 已整体迁出 PG（move-coordination-leases-to-redis）
-- =====================================================================
-- turn 执行租约与调度 fire lease 不再落库：改由 Redis 承载（owner-scoped Lua 原子裁决 +
-- 服务端 TTL 自过期 + runtime:turn-owners:<实例号> 二级索引供启动恢复枚举），
-- 键布局与脚本见 runtime.infrastructure.config.CoordLeaseKeys 与 resources/lua/runtime/。
-- 原协调租约表（含「类型 + 键」唯一索引与过期时间索引、列注释）随本基线一并移除；
-- 存量库若残留该同名表，已无任何代码读写，可择期人工 DROP。

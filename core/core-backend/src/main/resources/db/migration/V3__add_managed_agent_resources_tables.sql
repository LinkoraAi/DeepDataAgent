-- -----------------------------------------------------------
-- Flyway 迁移 v3：新增 Managed Agents 资源台账四表 + Agent 激活版本列
-- 表：memory_store（12）/ vault_secret（13）/ environment（14）/ deployment（15）
-- 变更：agent_definition 增加 active_version（最近发布与当前生效分离）
-- 说明：本期 userId / workspaceId 仅占位字段（默认值兜底），不做真实多租户透传与边界校验，
--       待用户体系 / 登录鉴权建立后再补全；基础字段同 V1/V2 约定。
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 12. 记忆库表（memory BC）
-- -----------------------------------------------------------
CREATE TABLE memory_store (
    id            BIGSERIAL     PRIMARY KEY,
    memory_id     VARCHAR(64)   NOT NULL,
    name          VARCHAR(64)   NOT NULL,
    type          VARCHAR(32)   NOT NULL DEFAULT 'SHORT_TERM',
    workspace_id  VARCHAR(64),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_memory_store_memory_id ON memory_store (memory_id) WHERE is_deleted = 0;

COMMENT ON TABLE  memory_store                 IS '记忆库表';
COMMENT ON COLUMN memory_store.id              IS '主键ID';
COMMENT ON COLUMN memory_store.memory_id       IS '记忆库业务ID';
COMMENT ON COLUMN memory_store.name            IS '记忆库名称';
COMMENT ON COLUMN memory_store.type            IS '记忆类型(SHORT_TERM短期/LONG_TERM长期)';
COMMENT ON COLUMN memory_store.workspace_id    IS '工作空间ID(占位，本期不做边界校验)';
COMMENT ON COLUMN memory_store.created_at      IS '创建时间';
COMMENT ON COLUMN memory_store.updated_at      IS '更新时间';
COMMENT ON COLUMN memory_store.created_by      IS '创建人';
COMMENT ON COLUMN memory_store.updated_by      IS '更新人';
COMMENT ON COLUMN memory_store.is_deleted      IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 13. 凭证密钥表（vault BC，密钥经独立密钥 AES/GCM 加密落库）
-- -----------------------------------------------------------
CREATE TABLE vault_secret (
    id              BIGSERIAL     PRIMARY KEY,
    secret_id       VARCHAR(64)   NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    encrypted_value TEXT          NOT NULL,
    workspace_id    VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    is_deleted      SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_vault_secret_secret_id ON vault_secret (secret_id) WHERE is_deleted = 0;

COMMENT ON TABLE  vault_secret                 IS '凭证密钥表(vault BC)';
COMMENT ON COLUMN vault_secret.id              IS '主键ID';
COMMENT ON COLUMN vault_secret.secret_id       IS '密钥业务ID';
COMMENT ON COLUMN vault_secret.name            IS '密钥名称';
COMMENT ON COLUMN vault_secret.encrypted_value IS '加密后的密钥值(AES/GCM)';
COMMENT ON COLUMN vault_secret.workspace_id    IS '工作空间ID(占位)';
COMMENT ON COLUMN vault_secret.created_at      IS '创建时间';
COMMENT ON COLUMN vault_secret.updated_at      IS '更新时间';
COMMENT ON COLUMN vault_secret.created_by      IS '创建人';
COMMENT ON COLUMN vault_secret.updated_by      IS '更新人';
COMMENT ON COLUMN vault_secret.is_deleted      IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 14. 运行环境表（agent BC，沙箱规格 JSONB）
-- -----------------------------------------------------------
CREATE TABLE environment (
    id             BIGSERIAL     PRIMARY KEY,
    environment_id VARCHAR(64)   NOT NULL,
    name           VARCHAR(64)   NOT NULL,
    type           VARCHAR(32)   NOT NULL DEFAULT 'LOCAL',
    sandbox_spec   JSONB         NOT NULL,
    workspace_id   VARCHAR(64),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_environment_environment_id ON environment (environment_id) WHERE is_deleted = 0;

COMMENT ON TABLE  environment                     IS '运行环境表';
COMMENT ON COLUMN environment.id                  IS '主键ID';
COMMENT ON COLUMN environment.environment_id      IS '运行环境业务ID';
COMMENT ON COLUMN environment.name                IS '运行环境名称';
COMMENT ON COLUMN environment.type                IS '环境类型(本期仅LOCAL本地Docker服务)';
COMMENT ON COLUMN environment.sandbox_spec        IS '沙箱规格(JSONB:image/memory/cpu/workspace_mode/timeout)';
COMMENT ON COLUMN environment.workspace_id        IS '工作空间ID(占位)';
COMMENT ON COLUMN environment.created_at          IS '创建时间';
COMMENT ON COLUMN environment.updated_at          IS '更新时间';
COMMENT ON COLUMN environment.created_by          IS '创建人';
COMMENT ON COLUMN environment.updated_by          IS '更新人';
COMMENT ON COLUMN environment.is_deleted          IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 15. 部署表（agent BC，激活/回滚审计记录，单向引用 agent）
-- -----------------------------------------------------------
CREATE TABLE deployment (
    id             BIGSERIAL     PRIMARY KEY,
    deployment_id  VARCHAR(64)   NOT NULL,
    agent_id       VARCHAR(64)   NOT NULL,
    version_number INTEGER       NOT NULL,
    workspace_id   VARCHAR(64),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_deployment_deployment_id ON deployment (deployment_id) WHERE is_deleted = 0;
CREATE INDEX idx_deployment_agent_id ON deployment (agent_id);

COMMENT ON TABLE  deployment                    IS '部署表(激活/回滚审计记录)';
COMMENT ON COLUMN deployment.id                 IS '主键ID';
COMMENT ON COLUMN deployment.deployment_id      IS '部署业务ID';
COMMENT ON COLUMN deployment.agent_id           IS '所属AgentID';
COMMENT ON COLUMN deployment.version_number     IS '激活/回滚的目标版本号';
COMMENT ON COLUMN deployment.workspace_id       IS '工作空间ID(占位)';
COMMENT ON COLUMN deployment.created_at         IS '创建时间';
COMMENT ON COLUMN deployment.updated_at         IS '更新时间';
COMMENT ON COLUMN deployment.created_by         IS '创建人';
COMMENT ON COLUMN deployment.updated_by         IS '更新人';
COMMENT ON COLUMN deployment.is_deleted         IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- agent_definition 增加 active_version（最近发布 vs 当前生效分离）
-- -----------------------------------------------------------
ALTER TABLE agent_definition ADD COLUMN active_version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_definition.active_version IS '当前生效版本号（默认随发布同步 latest_version，可回滚）';
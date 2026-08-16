-- -----------------------------------------------------------
-- Flyway 迁移 v2：新增 agent BC 台账四表
-- 表：agent_definition（8）/ agent_version（9）/
--      model_profile（10）/ skill_resource（11）
-- 说明：agent BC 四表原计划并入 V1（基线脚本），因 V1 已应用不可修改（checksum 校验），
--       独立为 V2 迁移脚本，保证迁移历史一致性与不可变约定。
--       全库基础字段统一为 created_at / updated_at / created_by / updated_by / is_deleted，
--       时间类型统一为 TIMESTAMPTZ（默认 now()）；基础字段由 MyBatis-Plus 自动填充。
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 8. Agent 定义表（agent BC）
-- -----------------------------------------------------------
CREATE TABLE agent_definition (
    id             BIGSERIAL    PRIMARY KEY,
    agent_id       VARCHAR(64)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    description    VARCHAR(500),
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    archived_at    TIMESTAMPTZ,
    latest_version INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    is_deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_agent_definition_agent_id ON agent_definition (agent_id) WHERE is_deleted = 0;
CREATE UNIQUE INDEX uk_agent_definition_name     ON agent_definition (name)     WHERE is_deleted = 0;

COMMENT ON TABLE  agent_definition                  IS 'Agent定义表';
COMMENT ON COLUMN agent_definition.id               IS '主键ID';
COMMENT ON COLUMN agent_definition.agent_id         IS 'Agent业务ID';
COMMENT ON COLUMN agent_definition.name             IS 'Agent名称';
COMMENT ON COLUMN agent_definition.description      IS 'Agent描述';
COMMENT ON COLUMN agent_definition.archived         IS '是否归档';
COMMENT ON COLUMN agent_definition.archived_at      IS '归档时间';
COMMENT ON COLUMN agent_definition.latest_version   IS '当前发布版本号（冗余，发布时事务内同步）';
COMMENT ON COLUMN agent_definition.created_at       IS '创建时间';
COMMENT ON COLUMN agent_definition.updated_at       IS '更新时间';
COMMENT ON COLUMN agent_definition.created_by       IS '创建人';
COMMENT ON COLUMN agent_definition.updated_by       IS '更新人';
COMMENT ON COLUMN agent_definition.is_deleted       IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 9. Agent 版本表（每次发布 = 一行快照，version_number 为发布号）
-- -----------------------------------------------------------
CREATE TABLE agent_version (
    id                  BIGSERIAL    PRIMARY KEY,
    version_id          VARCHAR(64)  NOT NULL,
    agent_id            VARCHAR(64)  NOT NULL,
    version_number      INTEGER      NOT NULL,
    name                VARCHAR(64)  NOT NULL,
    description         VARCHAR(500),
    system              TEXT         NOT NULL DEFAULT '',
    model_profile_id    VARCHAR(64)  NOT NULL,
    inference_params    JSONB,
    skill_ids           JSONB,
    knowledge_base_ids  JSONB,
    data_source_ids     JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    is_deleted          SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_agent_version_num ON agent_version (agent_id, version_number) WHERE is_deleted = 0;
CREATE INDEX idx_agent_version_agent_id  ON agent_version (agent_id);

COMMENT ON TABLE  agent_version                          IS 'Agent版本表（每次发布生成一行快照）';
COMMENT ON COLUMN agent_version.id                       IS '主键ID';
COMMENT ON COLUMN agent_version.version_id               IS '版本唯一标识';
COMMENT ON COLUMN agent_version.agent_id                 IS '所属AgentID';
COMMENT ON COLUMN agent_version.version_number           IS '发布版本号（同一Agent内递增，MAX+1）';
COMMENT ON COLUMN agent_version.name                     IS '版本名称';
COMMENT ON COLUMN agent_version.description              IS '版本描述';
COMMENT ON COLUMN agent_version.system                   IS '系统提示词';
COMMENT ON COLUMN agent_version.model_profile_id         IS '模型配置引用（profile改动对后续轮次生效）';
COMMENT ON COLUMN agent_version.inference_params         IS '推理参数(JSONB)';
COMMENT ON COLUMN agent_version.skill_ids                IS '挂载技能列表(JSONB:[{"skillId","version"}])';
COMMENT ON COLUMN agent_version.knowledge_base_ids       IS '知识库引用列表(JSONB，预留)';
COMMENT ON COLUMN agent_version.data_source_ids          IS '数据源引用列表(JSONB，关联datasource域)';
COMMENT ON COLUMN agent_version.created_at               IS '创建时间';
COMMENT ON COLUMN agent_version.updated_at               IS '更新时间';
COMMENT ON COLUMN agent_version.created_by               IS '创建人';
COMMENT ON COLUMN agent_version.updated_by               IS '更新人';
COMMENT ON COLUMN agent_version.is_deleted               IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 10. 模型配置表（凭证经 APP_MODEL_ENCRYPTION_KEY 独立密钥 AES/GCM 加密）
-- -----------------------------------------------------------
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
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             VARCHAR(100),
    updated_by             VARCHAR(100),
    is_deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_model_profile      ON model_profile (profile_id)   WHERE is_deleted = 0;
CREATE UNIQUE INDEX uk_model_profile_name ON model_profile (display_name) WHERE is_deleted = 0;

COMMENT ON TABLE  model_profile                        IS '模型配置表';
COMMENT ON COLUMN model_profile.id                     IS '主键ID';
COMMENT ON COLUMN model_profile.profile_id             IS '模型配置业务ID';
COMMENT ON COLUMN model_profile.display_name           IS '显示名称';
COMMENT ON COLUMN model_profile.description            IS '描述';
COMMENT ON COLUMN model_profile.api_format             IS 'API格式(AGENTSCOPE/OPENAI/BAILIAN…)';
COMMENT ON COLUMN model_profile.api_endpoint_url       IS 'API端点URL';
COMMENT ON COLUMN model_profile.model_name             IS '模型名称';
COMMENT ON COLUMN model_profile.encrypted_credential   IS '加密凭证(AES/GCM, 独立密钥)';
COMMENT ON COLUMN model_profile.model_series           IS '模型系列';
COMMENT ON COLUMN model_profile.context_window_input   IS '输入上下文窗口';
COMMENT ON COLUMN model_profile.context_window_output  IS '输出上下文窗口';
COMMENT ON COLUMN model_profile.tool_call_rounds       IS '工具调用轮次上限(对齐runtime maxIters)';
COMMENT ON COLUMN model_profile.model_type             IS '模型类型(1=CHAT 2=EMBEDDING)';
COMMENT ON COLUMN model_profile.vector_dimension       IS '向量维度(仅EMBEDDING, 领域层校验)';
COMMENT ON COLUMN model_profile.status                 IS '状态(ENABLED/DISABLED)';
COMMENT ON COLUMN model_profile.created_at             IS '创建时间';
COMMENT ON COLUMN model_profile.updated_at             IS '更新时间';
COMMENT ON COLUMN model_profile.created_by             IS '创建人';
COMMENT ON COLUMN model_profile.updated_by             IS '更新人';
COMMENT ON COLUMN model_profile.is_deleted             IS '删除标记(0=未删除,1=已删除)';

-- -----------------------------------------------------------
-- 11. 技能资源表（单表、每版本一行；存储经 SkillContentStore 端口抽象，暂仅 LOCAL_FILE）
-- -----------------------------------------------------------
CREATE TABLE skill_resource (
    id               BIGSERIAL    PRIMARY KEY,
    skill_id         VARCHAR(64)  NOT NULL,
    version_number   INTEGER      NOT NULL,
    name             VARCHAR(255) NOT NULL,
    description      VARCHAR(1000),
    skill_type       SMALLINT     NOT NULL DEFAULT 1,
    storage_type     VARCHAR(32)  NOT NULL DEFAULT 'LOCAL_FILE',
    storage_key      VARCHAR(500),
    content_sha256   CHAR(64)     NOT NULL,
    content_size     BIGINT       NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    is_deleted       SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_skill_version     ON skill_resource (skill_id, version_number) WHERE is_deleted = 0;
CREATE INDEX idx_skill_resource_skill_id ON skill_resource (skill_id);

COMMENT ON TABLE  skill_resource                    IS '技能资源表(每版本一行)';
COMMENT ON COLUMN skill_resource.id                 IS '主键ID';
COMMENT ON COLUMN skill_resource.skill_id           IS '技能业务ID';
COMMENT ON COLUMN skill_resource.version_number     IS '发布版本号';
COMMENT ON COLUMN skill_resource.name               IS '技能名称';
COMMENT ON COLUMN skill_resource.description        IS '技能描述';
COMMENT ON COLUMN skill_resource.skill_type         IS '技能类型(1=自定义 2=官方预留)';
COMMENT ON COLUMN skill_resource.storage_type       IS '存储类型(LOCAL_FILE/OSS预留)';
COMMENT ON COLUMN skill_resource.storage_key        IS '存储路径(本地相对路径或对象存储key)';
COMMENT ON COLUMN skill_resource.content_sha256      IS '内容SHA256校验';
COMMENT ON COLUMN skill_resource.content_size        IS '内容大小(字节)';
COMMENT ON COLUMN skill_resource.status              IS '状态(ACTIVE, 预留CHECKING/REJECTED)';
COMMENT ON COLUMN skill_resource.created_at          IS '创建时间';
COMMENT ON COLUMN skill_resource.updated_at          IS '更新时间';
COMMENT ON COLUMN skill_resource.created_by          IS '创建人';
COMMENT ON COLUMN skill_resource.updated_by          IS '更新人';
COMMENT ON COLUMN skill_resource.is_deleted          IS '删除标记(0=未删除,1=已删除)';
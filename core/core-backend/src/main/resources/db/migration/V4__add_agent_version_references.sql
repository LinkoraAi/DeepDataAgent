-- -----------------------------------------------------------
-- Flyway 迁移 v4：Agent 版本引用接线 + 多租户占位字段
-- 变更：agent_version 增 environment_id / memory_store_ids / workspace_id
--       agent_definition 增 workspace_id
-- 说明：本期 workspace_id 仅占位字段（可空，不做真实多租户透传与边界校验），
--       待用户体系 / 登录鉴权建立后再补全；基础字段同 V1/V2 约定。
-- -----------------------------------------------------------

-- Agent 版本引用字段：运行环境 + 记忆库引用
ALTER TABLE agent_version ADD COLUMN environment_id VARCHAR(64);
ALTER TABLE agent_version ADD COLUMN memory_store_ids JSONB;

COMMENT ON COLUMN agent_version.environment_id  IS '运行环境引用环境ID(可空，未引用回退系统默认规格)';
COMMENT ON COLUMN agent_version.memory_store_ids IS '记忆库引用列表(JSONB:["memory_store_id"]，可空)';

-- 多租户占位字段（本期不做边界过滤）
ALTER TABLE agent_version ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE agent_definition ADD COLUMN workspace_id VARCHAR(64);

COMMENT ON COLUMN agent_version.workspace_id    IS '工作空间ID(占位，本期不做边界过滤)';
COMMENT ON COLUMN agent_definition.workspace_id IS '工作空间ID(占位，本期不做边界过滤)';

CREATE INDEX idx_agent_version_environment_id ON agent_version (environment_id);
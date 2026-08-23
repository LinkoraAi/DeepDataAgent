-- -----------------------------------------------------------
-- Flyway 迁移 v7：agent_session 增加 workspace_id 占位列
-- 说明：本期 userId / workspaceId 仅占位（demo-user / 默认 default 工作空间），
--       不做真实多租户透传与边界校验，待用户体系 / 登录鉴权建立后再补全。
-- -----------------------------------------------------------

ALTER TABLE agent_session ADD COLUMN workspace_id VARCHAR(64) DEFAULT 'default';

COMMENT ON COLUMN agent_session.workspace_id IS '工作空间ID(占位，默认 default，本期不做边界校验)';
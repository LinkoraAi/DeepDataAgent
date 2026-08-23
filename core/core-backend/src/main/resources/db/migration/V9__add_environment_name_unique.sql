-- -----------------------------------------------------------
-- Flyway 迁移 v9：运行环境名称唯一性约束
-- 变更：environment 表新增 name 部分唯一索引（仅未删除行），落实领域模型 name「唯一」约定
-- 说明：本期 workspace_id 仅占位（统一 default 工作空间），名称在未删除行全局唯一；
--       待多租户透传建立后可迁移为 (workspace_id, name) 复合唯一索引。
-- -----------------------------------------------------------

CREATE UNIQUE INDEX uk_environment_name ON environment (name) WHERE is_deleted = 0;
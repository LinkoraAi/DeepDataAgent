-- -----------------------------------------------------------
-- Flyway 迁移 v6：技能包结构化资源（references / scripts）
-- 变更：skill_resource 新增 resource_manifest 列（JSONB，存 references / scripts 路径清单）
-- 兼容：新增列可空，既有技能包无结构化资源（读取时按空清单兜底）
-- -----------------------------------------------------------

ALTER TABLE skill_resource ADD COLUMN IF NOT EXISTS resource_manifest JSONB;

COMMENT ON COLUMN skill_resource.resource_manifest IS '结构化资源清单(JSONB:references/scripts文件路径数组)';
-- -----------------------------------------------------------
-- Flyway 迁移 v5：模型配置凭证支持「密钥引用」双模式
-- 变更：model_profile 新增 secret_id 列（引用模式仅记录密钥 ID，明文不落库）
-- 兼容：新增列可空，既有内嵌加密凭证明文路径不受影响（渐进迁移）
-- -----------------------------------------------------------

ALTER TABLE model_profile ADD COLUMN IF NOT EXISTS secret_id VARCHAR(64);

COMMENT ON COLUMN model_profile.secret_id IS '凭证引用的密钥ID(引用模式,明文不落库;与encrypted_credential互斥)';
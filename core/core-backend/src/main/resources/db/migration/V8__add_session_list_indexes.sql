-- -----------------------------------------------------------
-- Flyway 迁移 v8：会话列表查询复合索引
-- 说明：支撑「按 workspace_id + agent_id + status 过滤」的游标分页查询。
--       chat_event 的 (session_id, sequence_num) 唯一索引已在 V1 建立，
--       充当 sequence_number 幂等与回放去重的数据库兜底，此处无需重复创建。
-- -----------------------------------------------------------

CREATE INDEX idx_agent_session_workspace_agent_status ON agent_session (workspace_id, agent_id, status);
-- ===========================================================
-- DeepDataAgent - PostgreSQL 初始化脚本 00：创建三大扩展
-- 由官方 postgres 镜像 docker-entrypoint 在首次建库后按序执行
--  - vector    pgvector 向量检索
--  - zhparser  中英文全文检索分词（基于 SCWS）
--  - age       Apache AGE 图谱
-- ===========================================================

-- 1. 向量插件：pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 中英文分词插件：zhparser（基于 SCWS）
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 创建基于 zhparser 的全文检索配置并映射常用词性到 simple 词典
CREATE TEXT SEARCH CONFIGURATION zhparser_cfg (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION zhparser_cfg ADD MAPPING FOR n,v,a,i,e,l WITH simple;

-- 3. 图谱插件：Apache AGE
CREATE EXTENSION IF NOT EXISTS age;

-- 数据库级配置：
--   - session_preload_libraries 使每个新连接自动 LOAD 'age'
--   - search_path 默认包含 ag_catalog，应用无需手动 SET 即可直接使用 cypher()
ALTER DATABASE postgres SET session_preload_libraries = 'age';
ALTER DATABASE postgres SET search_path = ag_catalog, "$user", public;

-- 创建默认知识图谱（可通过 cypher() 函数使用）
SELECT create_graph('deepdata_graph');
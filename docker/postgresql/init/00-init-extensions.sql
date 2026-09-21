-- ===========================================================
-- DeepDataAgent - PostgreSQL 初始化脚本 00：创建两大扩展
-- 由镜像 docker-entrypoint 在首次建库后按序执行
--  - vector    pgvector 向量检索
--  - zhparser  中英文全文检索分词（基于 SCWS）
-- 注意：本脚本仅在数据卷首次初始化时执行，修改后需重建卷方可见效；
--       zhparser_cfg 名称与应用侧单点常量 ChunkTsvMapper.FULLTEXT_TS_CONFIG 一致。
-- ===========================================================

-- 1. 向量插件：pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 中英文分词插件：zhparser（基于 SCWS）
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 创建基于 zhparser 的全文检索配置并映射常用词性到 simple 词典。
-- 必须含 x（unknown，未翻译 token）：英文词、型号、编号类在 SCWS 下打标为 x，
-- 若不映射则文档与查询两侧对称丢弃，中文语料中的精确稀有词检索将失效。
CREATE TEXT SEARCH CONFIGURATION zhparser_cfg (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION zhparser_cfg ADD MAPPING FOR n,v,a,i,e,l,x WITH simple;

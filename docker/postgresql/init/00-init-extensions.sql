-- ===========================================================
-- DeepDataAgent - PostgreSQL 初始化脚本 00：创建三大扩展
-- 由镜像 docker-entrypoint 在首次建库后按序执行
--  - vector         pgvector 向量检索
--  - zhparser       中英文全文检索分词（基于 SCWS）
--  - pg_textsearch  BM25 全文检索（Timescale，需镜像 CMD 预加载才生效）
-- 注意：本脚本仅在数据卷首次初始化时执行，修改后需重建卷方可见效；
--       zhparser_cfg 名称与应用侧 V1 迁移中 idx_chunk_bm25_content 的 text_config 一致。
--       扩展按数据库安装：本脚本只作用于 POSTGRES_DB 指定的库；集成测试库、外部 PG 实例
--       以及任何新建业务库都必须各自执行 CREATE EXTENSION。
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

-- 3. BM25 全文检索插件：pg_textsearch（Timescale）
-- 依赖镜像 CMD 内置的 shared_preload_libraries=pg_textsearch；
-- 若容器启动时用自定义 command 覆盖了 CMD 且未补该参数，此处 CREATE 会直接报错
CREATE EXTENSION IF NOT EXISTS pg_textsearch;

-- 4. 扩展运行参数：保持上游默认，不在此覆盖
-- pg_textsearch.memory_limit 默认 2GB 是 memtable 缓存的总预算，本镜像刻意不改该默认值，
-- 改由容器侧 shm_size=4GB 提供等量的 /dev/shm 承载（见各 docker-compose 的 postgresql 服务）；
-- pg_textsearch.default_limit 默认 1000 同样保持默认，应用侧检索 SQL 均显式带 LIMIT。

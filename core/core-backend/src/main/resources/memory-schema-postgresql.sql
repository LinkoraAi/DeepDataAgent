-- Markdown 文件跟踪表
CREATE TABLE IF NOT EXISTS files (
    path         TEXT        PRIMARY KEY,
    layer        VARCHAR(20) NOT NULL,
    sub_category VARCHAR(50),
    file_hash    VARCHAR(64) NOT NULL,
    line_count   INTEGER     NOT NULL,
    updated_at   BIGINT      NOT NULL,
    is_indexed   SMALLINT    DEFAULT 1
);

COMMENT ON TABLE files IS 'Markdown 文件跟踪表：只记录文件级元数据和索引状态，Markdown 文件仍是长期记忆的唯一真相源';
COMMENT ON COLUMN files.path IS 'Markdown 文件相对路径，如 MEMORY.md、episodic/2026-04-21/session-xxx.md';
COMMENT ON COLUMN files.layer IS '记忆层级：episodic、semantic、skills';
COMMENT ON COLUMN files.sub_category IS '记忆子类别：event、fact、preference、rule、skill 等';
COMMENT ON COLUMN files.file_hash IS '文件内容 SHA-256（64 位十六进制），用于判断是否需要重建该文件索引';
COMMENT ON COLUMN files.line_count IS '文件总行数，用于来源定位和调试';
COMMENT ON COLUMN files.updated_at IS '最后一次索引更新时间，epoch millis';
COMMENT ON COLUMN files.is_indexed IS '索引状态：1=已索引，0=待索引或索引失效';

CREATE INDEX IF NOT EXISTS idx_files_layer ON files(layer);
CREATE INDEX IF NOT EXISTS idx_files_sub_category ON files(sub_category);

-- Markdown 分块索引表
CREATE TABLE IF NOT EXISTS chunks (
    id           TEXT        PRIMARY KEY,
    memory_id    VARCHAR(64),
    file_path    VARCHAR(500) NOT NULL,
    layer        VARCHAR(20)  NOT NULL,
    sub_category VARCHAR(50),
    start_line   INTEGER      NOT NULL,
    end_line     INTEGER      NOT NULL,
    content      TEXT         NOT NULL,
    importance   DOUBLE PRECISION DEFAULT 0.5,
    created_at   VARCHAR(40),
    access_count INTEGER      DEFAULT 0,
    updated_at   BIGINT       NOT NULL,
    FOREIGN KEY (file_path) REFERENCES files(path)
);

COMMENT ON TABLE chunks IS 'Markdown 分块索引表：content 是派生索引副本，检索命中后仍回读 file_path + 行号对应的 Markdown 原文';
COMMENT ON COLUMN chunks.id IS '分块 ID，通常由文件路径、行号和内容哈希生成';
COMMENT ON COLUMN chunks.memory_id IS '记忆条目 ID，如 mem-xxxxxxxx；无法解析时使用 chunk- 前缀兜底';
COMMENT ON COLUMN chunks.file_path IS '所属 Markdown 文件相对路径，对应 files.path';
COMMENT ON COLUMN chunks.layer IS '记忆层级，冗余保存以提升查询效率';
COMMENT ON COLUMN chunks.sub_category IS '子类别，冗余保存以支持分类检索和时间衰减';
COMMENT ON COLUMN chunks.start_line IS '分块在 Markdown 文件中的起始行号，1-based，包含该行';
COMMENT ON COLUMN chunks.end_line IS '分块在 Markdown 文件中的结束行号，1-based，包含该行';
COMMENT ON COLUMN chunks.content IS '分块文本内容，仅用于索引和排序，不作为真相源';
COMMENT ON COLUMN chunks.importance IS '重要性评分，范围建议 0.0-1.0，默认 0.5';
COMMENT ON COLUMN chunks.created_at IS '记忆创建时间，ISO-8601 字符串，用于时间衰减';
COMMENT ON COLUMN chunks.access_count IS '被召回次数，用于 recall boost';
COMMENT ON COLUMN chunks.updated_at IS '分块索引更新时间，epoch millis';

CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_path);
CREATE INDEX IF NOT EXISTS idx_chunks_layer ON chunks(layer);
CREATE INDEX IF NOT EXISTS idx_chunks_memory_id ON chunks(memory_id);

-- 全文检索表（原 SQLite FTS5 虚拟表 → 普通表 + tsvector 生成列 + GIN 索引）
-- 查询语法由 SQLite 的 MATCH 改为：content_tsv @@ plainto_tsquery('simple', ?)
CREATE TABLE IF NOT EXISTS chunks_fts (
    chunk_id     TEXT,
    content      TEXT,
    layer        VARCHAR(20),
    sub_category VARCHAR(50),
    memory_id    VARCHAR(64),
    file_path    VARCHAR(500),
    start_line   INTEGER,
    end_line     INTEGER,
    content_tsv  tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
);

COMMENT ON TABLE chunks_fts IS '全文检索表：PostgreSQL 全文检索方案（tsvector 生成列 + GIN 索引），替代 SQLite FTS5 虚拟表';
COMMENT ON COLUMN chunks_fts.chunk_id IS '对应 chunks.id，用于回表读取完整元数据';
COMMENT ON COLUMN chunks_fts.content IS '全文索引内容';
COMMENT ON COLUMN chunks_fts.layer IS '来源层级，仅随结果返回';
COMMENT ON COLUMN chunks_fts.sub_category IS '来源子类别，仅随结果返回';
COMMENT ON COLUMN chunks_fts.memory_id IS '来源记忆 ID，仅随结果返回';
COMMENT ON COLUMN chunks_fts.file_path IS '来源 Markdown 文件路径';
COMMENT ON COLUMN chunks_fts.start_line IS '来源起始行号';
COMMENT ON COLUMN chunks_fts.end_line IS '来源结束行号';
COMMENT ON COLUMN chunks_fts.content_tsv IS '全文索引向量，由 content 自动生成，随写入/更新自动维护';

CREATE INDEX IF NOT EXISTS idx_chunks_fts_content ON chunks_fts USING GIN (content_tsv);

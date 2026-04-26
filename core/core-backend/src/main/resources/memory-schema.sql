-- Markdown 文件跟踪表。
-- 只记录文件级元数据和索引状态；Markdown 文件仍是长期记忆的唯一真相源。
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,          -- Markdown 文件相对路径，如 MEMORY.md、episodic/2026-04-21/session-xxx.md。
    layer TEXT NOT NULL,            -- 记忆层级：episodic、semantic、skills。
    sub_category TEXT,              -- 记忆子类别：event、fact、preference、rule、skill 等。
    file_hash TEXT NOT NULL,        -- 文件内容 SHA-256，用于判断是否需要重建该文件索引。
    line_count INTEGER NOT NULL,    -- 文件总行数，用于来源定位和调试。
    updated_at INTEGER NOT NULL,    -- 最后一次索引更新时间，epoch millis。
    is_indexed INTEGER DEFAULT 1    -- 索引状态：1=已索引，0=待索引或索引失效。
);

-- 按层级/子类别快速过滤文件，便于后续增量重建或限定检索范围。
CREATE INDEX IF NOT EXISTS idx_files_layer ON files(layer);
CREATE INDEX IF NOT EXISTS idx_files_sub_category ON files(sub_category);

-- Markdown 分块索引表。
-- content 是从 Markdown 派生出的索引副本；检索命中后仍应回读 file_path + 行号对应的 Markdown 原文。
CREATE TABLE IF NOT EXISTS chunks (
    id TEXT PRIMARY KEY,            -- 分块 ID，通常由文件路径、行号和内容哈希生成。
    memory_id TEXT,                 -- 记忆条目 ID，如 mem-xxxxxxxx；无法解析时使用 chunk- 前缀兜底。
    file_path TEXT NOT NULL,        -- 所属 Markdown 文件相对路径，对应 files.path。
    layer TEXT NOT NULL,            -- 记忆层级，冗余保存以提升查询效率。
    sub_category TEXT,              -- 子类别，冗余保存以支持分类检索和时间衰减。
    start_line INTEGER NOT NULL,    -- 分块在 Markdown 文件中的起始行号，1-based，包含该行。
    end_line INTEGER NOT NULL,      -- 分块在 Markdown 文件中的结束行号，1-based，包含该行。
    content TEXT NOT NULL,          -- 分块文本内容，仅用于索引和排序，不作为真相源。
    importance REAL DEFAULT 0.5,    -- 重要性评分，范围建议 0.0-1.0，默认 0.5。
    created_at TEXT,                -- 记忆创建时间，ISO-8601 字符串，用于时间衰减。
    access_count INTEGER DEFAULT 0, -- 被召回次数，用于 recall boost。
    updated_at INTEGER NOT NULL,    -- 分块索引更新时间，epoch millis。
    FOREIGN KEY (file_path) REFERENCES files(path)
);

-- 常用检索路径索引：按文件重建、按层级/记忆 ID 查询。
CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_path);
CREATE INDEX IF NOT EXISTS idx_chunks_layer ON chunks(layer);
CREATE INDEX IF NOT EXISTS idx_chunks_memory_id ON chunks(memory_id);

-- FTS5 全文检索表。
-- 只有 content 参与全文索引；其他字段 UNINDEXED 仅用于返回来源归因。
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    chunk_id UNINDEXED,             -- 对应 chunks.id，用于回表读取完整元数据。
    content,                        -- FTS5 全文索引内容。
    layer UNINDEXED,                -- 来源层级，仅随结果返回，不参与全文索引。
    sub_category UNINDEXED,         -- 来源子类别，仅随结果返回，不参与全文索引。
    memory_id UNINDEXED,            -- 来源记忆 ID，仅随结果返回，不参与全文索引。
    file_path UNINDEXED,            -- 来源 Markdown 文件路径。
    start_line UNINDEXED,           -- 来源起始行号。
    end_line UNINDEXED              -- 来源结束行号。
);

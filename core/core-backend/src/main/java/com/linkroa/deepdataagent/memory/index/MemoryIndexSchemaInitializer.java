package com.linkroa.deepdataagent.memory.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates the disposable SQLite index schema used by the memory module.
 */
public class MemoryIndexSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public MemoryIndexSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean initializeSchema() {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    path TEXT PRIMARY KEY,
                    layer TEXT NOT NULL,
                    sub_category TEXT,
                    file_hash TEXT NOT NULL,
                    line_count INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    is_indexed INTEGER DEFAULT 1
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_files_layer ON files(layer)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_files_sub_category ON files(sub_category)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    memory_id TEXT,
                    file_path TEXT NOT NULL,
                    layer TEXT NOT NULL,
                    sub_category TEXT,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    importance REAL DEFAULT 0.5,
                    created_at TEXT,
                    access_count INTEGER DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (file_path) REFERENCES files(path)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_path)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunks_layer ON chunks(layer)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunks_memory_id ON chunks(memory_id)");
        return initializeFtsTable();
    }

    private boolean initializeFtsTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                        chunk_id UNINDEXED,
                        content,
                        layer UNINDEXED,
                        sub_category UNINDEXED,
                        memory_id UNINDEXED,
                        file_path UNINDEXED,
                        start_line UNINDEXED,
                        end_line UNINDEXED
                    )
                    """);
            return true;
        } catch (DataAccessException e) {
            log.warn("SQLite FTS5 is unavailable; memory search will fall back to Java scanning: {}", e.getMessage());
            return false;
        }
    }
}

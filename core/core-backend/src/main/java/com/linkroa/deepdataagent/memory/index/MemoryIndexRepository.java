package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.ScoredChunk;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static com.linkroa.deepdataagent.memory.config.MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN;

/**
 * SQL boundary for the memory index.
 */
@Repository
public class MemoryIndexRepository {

    private static final String SELECT_CHUNKS = "SELECT * FROM chunks";

    private final JdbcTemplate jdbcTemplate;

    public MemoryIndexRepository(@Qualifier(JDBC_TEMPLATE_BEAN) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clearAll(boolean ftsAvailable) {
        jdbcTemplate.update("DELETE FROM chunks");
        jdbcTemplate.update("DELETE FROM files");
        if (ftsAvailable) {
            jdbcTemplate.update("DELETE FROM chunks_fts");
        }
    }

    public String currentFileHash(String relativePath) {
        List<String> hashes = jdbcTemplate.query(
                "SELECT file_hash FROM files WHERE path = ?",
                (rs, rowNum) -> rs.getString("file_hash"),
                relativePath
        );
        return hashes.isEmpty() ? null : hashes.getFirst();
    }

    public void replaceFileChunks(
            String relativePath,
            String layer,
            String subCategory,
            String hash,
            int lineCount,
            long updatedAt,
            List<MemoryChunk> chunks,
            boolean ftsAvailable
    ) {
        deleteChunksForFile(relativePath, ftsAvailable);
        jdbcTemplate.update("""
                INSERT INTO files(path, layer, sub_category, file_hash, line_count, updated_at, is_indexed)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(path) DO UPDATE SET
                  layer = excluded.layer,
                  sub_category = excluded.sub_category,
                  file_hash = excluded.file_hash,
                  line_count = excluded.line_count,
                  updated_at = excluded.updated_at,
                  is_indexed = 1
                """, relativePath, layer, subCategory, hash, lineCount, updatedAt);
        insertChunks(chunks);
        if (ftsAvailable) {
            insertFtsRows(chunks);
        }
    }

    public void removeFile(String relativePath, boolean ftsAvailable) {
        deleteChunksForFile(relativePath, ftsAvailable);
        jdbcTemplate.update("DELETE FROM files WHERE path = ?", relativePath);
    }

    public List<ScoredChunk> ftsSearch(String ftsQuery, int limit) {
        if (ftsQuery == null || ftsQuery.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT c.*, bm25(chunks_fts) AS rank
                FROM chunks_fts
                JOIN chunks c ON c.id = chunks_fts.chunk_id
                WHERE chunks_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """, (rs, rowNum) -> {
            double rank = Math.abs(rs.getDouble("rank"));
            return new ScoredChunk(mapChunk(rs, rowNum), 1.0 / (1.0 + rank));
        }, ftsQuery, Math.max(limit, 1));
    }

    public List<MemoryChunk> loadAllChunks() {
        return jdbcTemplate.query(SELECT_CHUNKS + " ORDER BY updated_at DESC", MemoryIndexRepository::mapChunk);
    }

    public MemoryChunk findChunk(String chunkId) {
        List<MemoryChunk> chunks = jdbcTemplate.query(
                SELECT_CHUNKS + " WHERE id = ?",
                MemoryIndexRepository::mapChunk,
                chunkId
        );
        return chunks.isEmpty() ? null : chunks.getFirst();
    }

    public void updateAccessCounts(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(chunkIds);
        jdbcTemplate.batchUpdate("UPDATE chunks SET access_count = access_count + 1 WHERE id = ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                        ps.setString(1, ids.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
    }

    private void deleteChunksForFile(String relativePath, boolean ftsAvailable) {
        if (ftsAvailable) {
            jdbcTemplate.update(
                    "DELETE FROM chunks_fts WHERE chunk_id IN (SELECT id FROM chunks WHERE file_path = ?)",
                    relativePath
            );
        }
        jdbcTemplate.update("DELETE FROM chunks WHERE file_path = ?", relativePath);
    }

    private void insertChunks(List<MemoryChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO chunks(id, memory_id, file_path, layer, sub_category, start_line, end_line,
                                   content, importance, created_at, access_count, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                bindChunk(ps, chunks.get(i));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    private void insertFtsRows(List<MemoryChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO chunks_fts(chunk_id, content, layer, sub_category, memory_id, file_path, start_line, end_line)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                bindFts(ps, chunks.get(i));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    private static void bindChunk(PreparedStatement ps, MemoryChunk chunk) throws SQLException {
        ps.setString(1, chunk.id());
        ps.setString(2, chunk.memoryId());
        ps.setString(3, chunk.filePath());
        ps.setString(4, chunk.layer());
        ps.setString(5, chunk.subCategory());
        ps.setInt(6, chunk.startLine());
        ps.setInt(7, chunk.endLine());
        ps.setString(8, chunk.content());
        ps.setDouble(9, chunk.importance());
        ps.setString(10, chunk.createdAt().toString());
        ps.setInt(11, chunk.accessCount());
        ps.setLong(12, chunk.updatedAt());
    }

    private static void bindFts(PreparedStatement ps, MemoryChunk chunk) throws SQLException {
        ps.setString(1, chunk.id());
        ps.setString(2, chunk.content());
        ps.setString(3, chunk.layer());
        ps.setString(4, chunk.subCategory());
        ps.setString(5, chunk.memoryId());
        ps.setString(6, chunk.filePath());
        ps.setInt(7, chunk.startLine());
        ps.setInt(8, chunk.endLine());
    }

    private static MemoryChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        String createdAt = rs.getString("created_at");
        Instant instant;
        try {
            instant = createdAt == null || createdAt.isBlank() ? Instant.now() : Instant.parse(createdAt);
        } catch (RuntimeException e) {
            instant = Instant.now();
        }
        return new MemoryChunk(
                rs.getString("id"),
                rs.getString("memory_id"),
                rs.getString("file_path"),
                rs.getString("layer"),
                rs.getString("sub_category"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getString("content"),
                rs.getDouble("importance"),
                instant,
                rs.getInt("access_count"),
                rs.getLong("updated_at")
        );
    }
}

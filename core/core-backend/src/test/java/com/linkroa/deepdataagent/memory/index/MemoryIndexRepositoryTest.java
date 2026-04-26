package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MemoryIndexRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private MemoryIndexRepository repository;
    private boolean ftsAvailable;

    @BeforeEach
    void setUp() {
        // given
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        ftsAvailable = new MemoryIndexSchemaInitializer(jdbcTemplate).initializeSchema();
        repository = new MemoryIndexRepository(jdbcTemplate);
    }

    @Test
    void should_replaceFindSearchAndUpdateChunks_when_repositoryMethodsInvoked_given_validChunks() {
        // given
        String path = "semantic/facts.md";
        MemoryChunk chunk = chunk("chunk-cache", "mem-cache", path, "Redis cache stores hot query results.");

        // when
        repository.replaceFileChunks(path, "semantic", "fact", "hash-1", 2, 100L, List.of(chunk), ftsAvailable);
        String currentHash = repository.currentFileHash(path);
        MemoryChunk found = repository.findChunk("chunk-cache");
        var allChunks = repository.loadAllChunks();
        var ftsResults = repository.ftsSearch("\"Redis\"", 5);

        repository.updateAccessCounts(List.of("chunk-cache"));
        MemoryChunk accessed = repository.findChunk("chunk-cache");

        // then
        assertEquals("hash-1", currentHash);
        assertEquals("mem-cache", found.memoryId());
        assertEquals(1, allChunks.size());
        assertFalse(ftsResults.isEmpty());
        assertEquals(1, accessed.accessCount());
    }

    @Test
    void should_replaceExistingFileAndRemoveOldChunks_when_replaceFileChunks_given_sameFileWithNewContent() {
        // given
        String path = "semantic/facts.md";
        MemoryChunk oldChunk = chunk("chunk-old", "mem-old", path, "old content");
        MemoryChunk newChunk = chunk("chunk-new", "mem-new", path, "new content");
        repository.replaceFileChunks(path, "semantic", "fact", "hash-old", 1, 100L, List.of(oldChunk), ftsAvailable);

        // when
        repository.replaceFileChunks(path, "semantic", "fact", "hash-new", 1, 200L, List.of(newChunk), ftsAvailable);
        MemoryChunk oldResult = repository.findChunk("chunk-old");
        MemoryChunk newResult = repository.findChunk("chunk-new");

        // then
        assertNull(oldResult);
        assertEquals("hash-new", repository.currentFileHash(path));
        assertEquals("mem-new", newResult.memoryId());
    }

    @Test
    void should_ignoreEmptyInputsAndMapInvalidCreatedAt_when_repositoryMethodsInvoked_given_edgeCaseRows() {
        // given
        repository.updateAccessCounts(null);
        repository.updateAccessCounts(List.of());
        jdbcTemplate.update("""
                INSERT INTO files(path, layer, sub_category, file_hash, line_count, updated_at, is_indexed)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """, "semantic/bad.md", "semantic", "fact", "bad-hash", 1, 1L);
        jdbcTemplate.update("""
                        INSERT INTO chunks(id, memory_id, file_path, layer, sub_category, start_line, end_line,
                                           content, importance, created_at, access_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, "chunk-bad-date", "mem-bad", "semantic/bad.md", "semantic", "fact",
                1, 1, "bad date content", 0.5, "not-an-instant", 0, 1L);

        // when
        MemoryChunk chunk = repository.findChunk("chunk-bad-date");
        var blankFtsResults = repository.ftsSearch("   ", 5);

        // then
        assertEquals("chunk-bad-date", chunk.id());
        assertTrue(blankFtsResults.isEmpty());
    }

    @Test
    void should_removeAndClearIndexes_when_removeFileAndClearAll_given_indexedChunks() {
        // given
        MemoryChunk first = chunk("chunk-first", "mem-first", "semantic/first.md", "first content");
        MemoryChunk second = chunk("chunk-second", "mem-second", "semantic/second.md", "second content");
        repository.replaceFileChunks("semantic/first.md", "semantic", "fact", "hash-1", 1, 100L, List.of(first), ftsAvailable);
        repository.replaceFileChunks("semantic/second.md", "semantic", "fact", "hash-2", 1, 100L, List.of(second), ftsAvailable);

        // when
        repository.removeFile("semantic/first.md", ftsAvailable);
        MemoryChunk removed = repository.findChunk("chunk-first");
        MemoryChunk remaining = repository.findChunk("chunk-second");
        repository.clearAll(ftsAvailable);

        // then
        assertNull(removed);
        assertEquals("chunk-second", remaining.id());
        assertTrue(repository.loadAllChunks().isEmpty());
    }

    @Test
    void should_skipChunkAndFtsBatchInsert_when_replaceFileChunks_given_emptyChunksAndFtsDisabled() {
        // given
        String path = "semantic/empty.md";

        // when
        repository.replaceFileChunks(path, "semantic", "fact", "empty-hash", 0, 100L, List.of(), false);

        // then
        assertEquals("empty-hash", repository.currentFileHash(path));
        assertTrue(repository.loadAllChunks().isEmpty());
    }

    private static MemoryChunk chunk(String id, String memoryId, String path, String content) {
        return new MemoryChunk(
                id,
                memoryId,
                path,
                "semantic",
                "fact",
                1,
                2,
                content,
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                0,
                1L
        );
    }
}

package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryIndexManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    private MarkdownFileManager fileManager;

    @Mock
    private MarkdownChunker chunker;

    private MemoryIndexManager indexManager;

    @BeforeEach
    void setUp() throws IOException {
        MemoryProperties properties = new MemoryProperties();
        properties.getIndex().setRebuildOnStartup(false);
        Path dbPath = tempDir.resolve(".index").resolve("memory.db");
        Files.createDirectories(dbPath.getParent());
        properties.getIndex().setDbPath(dbPath.toString());

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        indexManager = new MemoryIndexManager(
                properties,
                fileManager,
                chunker,
                new MemoryIndexSchemaInitializer(jdbcTemplate),
                new MemoryIndexRepository(jdbcTemplate),
                transactionTemplate
        );
        indexManager.initialize();
    }

    @Test
    void should_indexSearchAndRemoveChunks_when_syncIncremental_given_singleMarkdownFile() {
        // given
        String path = "semantic/facts.md";
        String content = """
                ### Redis cache [id: mem-cache] [importance: 0.8]
                Redis cache stores hot query results.
                """;
        MemoryChunk chunk = chunk("chunk-cache", "mem-cache", path, "semantic", "fact", content);
        when(fileManager.readIfExists(path)).thenReturn(content, "");
        when(chunker.chunk(path, content)).thenReturn(List.of(chunk));
        when(chunker.layerOf(path, content)).thenReturn("semantic");
        when(chunker.subCategoryOf(path, content)).thenReturn("fact");

        // when
        indexManager.syncIncremental(List.of(path));
        var results = indexManager.keywordSearch("Redis cache", 5);

        // then
        assertFalse(results.isEmpty());
        assertEquals("mem-cache", results.getFirst().chunk().memoryId());

        indexManager.updateAccessCounts(List.of(results.getFirst().chunk().id()));
        assertEquals(1, indexManager.findChunk(results.getFirst().chunk().id()).accessCount());

        indexManager.syncIncremental(List.of(path));
        assertTrue(indexManager.keywordSearch("Redis cache", 5).isEmpty());
    }

    @Test
    void should_rebuildIndexesFromTruthSource_when_rebuildAllIndexes_given_mockedMarkdownWorkspace() {
        // given
        String path = "skills/deploy.md";
        String content = """
                ---
                layer: skills
                sub_category: skill
                created_at: 2026-04-21T00:00:00Z
                ---
                
                ### Deploy skill [id: mem-deploy] [importance: 0.9]
                Deploy Spring Boot service with health checks.
                """;
        MemoryChunk chunk = chunk(
                "chunk-deploy",
                "mem-deploy",
                path,
                "skills",
                "skill",
                "Deploy Spring Boot service with health checks."
        );
        when(fileManager.scanMarkdownFiles()).thenReturn(List.of(path));
        when(fileManager.readIfExists(path)).thenReturn(content);
        when(chunker.chunk(path, content)).thenReturn(List.of(chunk));
        when(chunker.layerOf(path, content)).thenReturn("skills");
        when(chunker.subCategoryOf(path, content)).thenReturn("skill");

        // when
        indexManager.rebuildAllIndexes();
        var results = indexManager.keywordSearch("health checks", 5);

        // then
        assertFalse(results.isEmpty());
        assertNotNull(indexManager.findChunk(results.getFirst().chunk().id()));
        assertEquals("skills", results.getFirst().chunk().layer());
        verify(fileManager).scanMarkdownFiles();
    }

    private static MemoryChunk chunk(String id, String memoryId, String path, String layer, String subCategory, String content) {
        return new MemoryChunk(
                id,
                memoryId,
                path,
                layer,
                subCategory,
                1,
                2,
                content,
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                0,
                System.currentTimeMillis()
        );
    }
}

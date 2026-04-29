package com.linkroa.deepdataagent.memory.vector;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.embedding.HashingMemoryEmbeddingModel;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JVectorMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void should_persistAndSearchChunks_when_rebuild_given_memoryChunks() {
        // given
        MemoryProperties properties = properties();
        JVectorMemoryStore store = new JVectorMemoryStore(properties, new HashingMemoryEmbeddingModel(properties));
        MemoryChunk healthCheck = chunk(
                "chunk-health",
                "mem-health",
                "skills/deploy.md",
                "PostgreSQL readiness health check for Spring Boot deployment"
        );
        MemoryChunk uiTheme = chunk(
                "chunk-theme",
                "mem-theme",
                "semantic/preferences.md",
                "Vue theme palette and button spacing preference"
        );

        // when
        store.rebuild(List.of(healthCheck, uiTheme));
        List<VectorSearchHit> hits = store.search("postgres readiness health", 2);

        // then
        assertFalse(hits.isEmpty());
        assertEquals("chunk-health", hits.getFirst().chunkId());
        assertTrue(Files.exists(tempDir.resolve(".index").resolve("jvector").resolve("memory-vectors.graph")));
        assertTrue(Files.exists(tempDir.resolve(".index").resolve("jvector").resolve("memory-vectors.metadata")));

        // and persisted JVector files can be loaded by a new store instance
        store.close();
        JVectorMemoryStore loaded = new JVectorMemoryStore(properties, new HashingMemoryEmbeddingModel(properties));
        try {
            List<VectorSearchHit> loadedHits = loaded.search("postgres readiness health", 2);
            assertFalse(loadedHits.isEmpty());
            assertEquals("chunk-health", loadedHits.getFirst().chunkId());
        } finally {
            loaded.close();
        }
    }

    @Test
    void should_returnEmptyAndWriteNoFiles_when_disabled() {
        // given
        MemoryProperties properties = properties();
        properties.getVector().setEnabled(false);
        JVectorMemoryStore store = new JVectorMemoryStore(properties, new HashingMemoryEmbeddingModel(properties));

        // when
        store.rebuild(List.of(chunk("chunk-health", "mem-health", "skills/deploy.md", "health check")));

        // then
        assertEquals(List.of(), store.search("health", 3));
        assertFalse(Files.exists(tempDir.resolve(".index").resolve("jvector").resolve("memory-vectors.graph")));
    }

    private MemoryProperties properties() {
        MemoryProperties properties = new MemoryProperties();
        properties.setRootPath(tempDir.toString());
        properties.getVector().setDimension(64);
        properties.getVector().setDistanceMetric("cosine");
        return properties;
    }

    private static MemoryChunk chunk(String id, String memoryId, String filePath, String content) {
        return new MemoryChunk(
                id,
                memoryId,
                filePath,
                filePath.startsWith("skills/") ? "skills" : "semantic",
                filePath.startsWith("skills/") ? "skill" : "preference",
                1,
                3,
                content,
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                0,
                1L
        );
    }
}

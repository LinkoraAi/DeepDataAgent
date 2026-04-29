package com.linkroa.deepdataagent.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;

class DeepLongMemoryBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void should_buildInitializeAndCloseMemoryInfrastructure_when_directBuilderUsed() throws Exception {
        MemoryProperties properties = new MemoryProperties();
        properties.setRootPath(tempDir.toString());
        properties.getVector().setDimension(64);
        properties.getIndex().setRebuildOnStartup(false);

        DeepLongMemory memory = DeepLongMemory.builder()
                .sessionId("session-built")
                .userName("user-built")
                .properties(properties)
                .build();

        assertNotNull(memory);
        assertTrue(Files.exists(tempDir.resolve("MEMORY.md")));
        assertTrue(Files.exists(tempDir.resolve("USER.md")));
        assertTrue(Files.exists(tempDir.resolve(".index")));

        assertDoesNotThrow(memory::close);
    }
}

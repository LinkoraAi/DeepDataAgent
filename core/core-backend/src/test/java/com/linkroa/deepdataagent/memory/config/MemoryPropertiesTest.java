package com.linkroa.deepdataagent.memory.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MemoryPropertiesTest {

    @Test
    void should_returnDocumentedDefaults_when_accessorsInvoked_given_defaultProperties() {
        // given
        MemoryProperties properties = new MemoryProperties();

        // when
        String rootPath = properties.getRootPath();

        // then
        assertEquals("./data/memory", rootPath);
        assertEquals(1200, properties.getChunking().getMaxChars());
        assertEquals(120, properties.getChunking().getOverlapChars());
        assertEquals(8, properties.getRetrieve().getTopK());
        assertEquals(4000, properties.getRetrieve().getMaxChars());
        assertEquals(60, properties.getRetrieve().getRrfK());
        assertEquals(0.0, properties.getRetrieve().getMinScore(), 0.0001);
        assertEquals(2, properties.getRecord().getMinRoundSize());
        assertTrue(properties.getRecord().isCaptureFullConversation());
        assertEquals("", properties.getIndex().getDbPath());
        assertTrue(properties.getIndex().isRebuildOnStartup());
        assertEquals(List.of("MEMORY.md", "USER.md"), properties.getIo().getCache().getPreloadFiles());
        assertTrue(properties.getIo().getCache().isFlushOnRecord());
        assertEquals(200, properties.getIo().getCache().getMaxCacheSize());
        assertEquals(0.2, properties.getTemporal().getRecallBoostFactor(), 0.0001);
    }

    @Test
    void should_returnOverriddenNestedOptions_when_accessorsInvoked_given_customProperties() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.setRootPath("target/memory");
        properties.getChunking().setMaxChars(256);
        properties.getRetrieve().setTopK(3);
        properties.getRecord().setCaptureFullConversation(false);
        properties.getIndex().setDbPath("target/memory.db");
        properties.getIo().getCache().setPreloadFiles(List.of("MEMORY.md"));
        properties.getTemporal().setRecallBoostFactor(0.4);

        // when
        String rootPath = properties.getRootPath();
        int maxChars = properties.getChunking().getMaxChars();
        int topK = properties.getRetrieve().getTopK();
        boolean captureFullConversation = properties.getRecord().isCaptureFullConversation();
        String dbPath = properties.getIndex().getDbPath();
        List<String> preloadFiles = properties.getIo().getCache().getPreloadFiles();
        double recallBoostFactor = properties.getTemporal().getRecallBoostFactor();

        // then
        assertEquals("target/memory", rootPath);
        assertEquals(256, maxChars);
        assertEquals(3, topK);
        assertFalse(captureFullConversation);
        assertEquals("target/memory.db", dbPath);
        assertEquals(List.of("MEMORY.md"), preloadFiles);
        assertEquals(0.4, recallBoostFactor, 0.0001);
    }
}

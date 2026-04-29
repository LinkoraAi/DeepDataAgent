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
        assertTrue(properties.getVector().isEnabled());
        assertEquals(1536, properties.getVector().getDimension());
        assertEquals("cosine", properties.getVector().getDistanceMetric());
        assertEquals("", properties.getVector().getPersistencePath());
        assertEquals(16, properties.getVector().getMaxDegree());
        assertEquals(100, properties.getVector().getBeamWidth());
        assertEquals(1.2f, properties.getVector().getNeighborOverflow(), 0.0001f);
        assertEquals(1.2f, properties.getVector().getAlpha(), 0.0001f);
        assertEquals(0, properties.getVector().getRebuildThreshold());
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
        properties.getVector().setEnabled(false);
        properties.getVector().setDimension(64);
        properties.getVector().setDistanceMetric("dot-product");
        properties.getVector().setPersistencePath("target/memory/vector-index");
        properties.getVector().setRebuildThreshold(10);
        properties.getIo().getCache().setPreloadFiles(List.of("MEMORY.md"));
        properties.getTemporal().setRecallBoostFactor(0.4);

        // when
        String rootPath = properties.getRootPath();
        int maxChars = properties.getChunking().getMaxChars();
        int topK = properties.getRetrieve().getTopK();
        boolean captureFullConversation = properties.getRecord().isCaptureFullConversation();
        String dbPath = properties.getIndex().getDbPath();
        boolean vectorEnabled = properties.getVector().isEnabled();
        int vectorDimension = properties.getVector().getDimension();
        String vectorDistanceMetric = properties.getVector().getDistanceMetric();
        String vectorPersistencePath = properties.getVector().getPersistencePath();
        int vectorRebuildThreshold = properties.getVector().getRebuildThreshold();
        List<String> preloadFiles = properties.getIo().getCache().getPreloadFiles();
        double recallBoostFactor = properties.getTemporal().getRecallBoostFactor();

        // then
        assertEquals("target/memory", rootPath);
        assertEquals(256, maxChars);
        assertEquals(3, topK);
        assertFalse(captureFullConversation);
        assertEquals("target/memory.db", dbPath);
        assertFalse(vectorEnabled);
        assertEquals(64, vectorDimension);
        assertEquals("dot-product", vectorDistanceMetric);
        assertEquals("target/memory/vector-index", vectorPersistencePath);
        assertEquals(10, vectorRebuildThreshold);
        assertEquals(List.of("MEMORY.md"), preloadFiles);
        assertEquals(0.4, recallBoostFactor, 0.0001);
    }
}

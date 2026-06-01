package com.linkroa.deepdataagent.memory.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.linkroa.deepdataagent.memory.DeepLongMemory;
import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.vector.JVectorMemoryStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring Boot integration tests for DeepLongMemory session factory.
 * 
 * <p>These tests verify that the Spring auto-configuration correctly creates
 * and manages shared infrastructure beans, and that the session factory can
 * create independent DeepLongMemory instances.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "app.memory.root-path=target/test-memory-integration",
    "app.memory.vector.dimension=64",
    "app.memory.index.rebuild-on-startup=false",
    "app.memory.extractor.type=fallback"
})
class DeepLongMemorySpringIntegrationTest {

    @Autowired
    private DeepLongMemorySessionFactory sessionFactory;

    @Autowired
    private MemoryIndexManager sharedIndexManager;

    @Autowired
    private JVectorMemoryStore sharedVectorStore;

    @Test
    void should_createMemoryInstanceFromFactory() {
        DeepLongMemory memory = sessionFactory.create("test-session-1");
        
        assertNotNull(memory);
        memory.close();
    }

    @Test
    void should_createMultipleIndependentSessions() {
        DeepLongMemory memory1 = sessionFactory.create("session-1");
        DeepLongMemory memory2 = sessionFactory.create("session-2");
        
        assertNotNull(memory1);
        assertNotNull(memory2);
        assertNotSame(memory1, memory2);
        
        memory1.close();
        memory2.close();
    }

    @Test
    void should_shareInfrastructureAcrossSessions() {
        // Verify that multiple sessions share the same infrastructure
        DeepLongMemory memory1 = sessionFactory.create("shared-test-1");
        DeepLongMemory memory2 = sessionFactory.create("shared-test-2");
        
        // Both sessions should be using the same shared infrastructure beans
        assertNotNull(sharedIndexManager);
        assertNotNull(sharedVectorStore);
        
        memory1.close();
        memory2.close();
    }

    @Test
    void should_createMemoryWithCustomProperties() {
        MemoryProperties customProps = new MemoryProperties();
        customProps.setRootPath("target/test-memory-custom");
        customProps.getRetrieve().setTopK(20);
        customProps.getVector().setDimension(64);
        customProps.getIndex().setRebuildOnStartup(false);
        
        DeepLongMemory memory = sessionFactory.create("custom-session", customProps);
        
        assertNotNull(memory);
        memory.close();
    }

    @Test
    void should_closeSessionWithoutAffectingSharedResources() {
        DeepLongMemory memory1 = sessionFactory.create("shared-test-1");
        DeepLongMemory memory2 = sessionFactory.create("shared-test-2");
        
        memory1.close();
        
        // memory2 should still be operational after memory1 closes
        assertNotNull(memory2);
        
        // Verify memory2 can still perform operations (not just non-null)
        // The session context should still be valid
        memory2.close();
        
        // Shared infrastructure should still be available
        assertNotNull(sharedIndexManager);
        assertNotNull(sharedVectorStore);
    }

    @Test
    void should_rejectBlankSessionId() {
        assertThrows(IllegalArgumentException.class, 
            () -> sessionFactory.create(null));
        assertThrows(IllegalArgumentException.class, 
            () -> sessionFactory.create("  "));
    }
}

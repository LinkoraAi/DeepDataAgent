package com.linkroa.deepdataagent.memory.spring;

import java.util.Objects;

import com.linkroa.deepdataagent.memory.DeepLongMemory;
import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.extractor.MemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;
import com.linkroa.deepdataagent.memory.vector.JVectorMemoryStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Factory for creating session-scoped DeepLongMemory instances
 * that share Spring-managed infrastructure resources.
 * 
 * <p>This factory holds references to shared infrastructure beans (DataSource, JdbcTemplate,
 * TransactionTemplate, VectorStore, IndexManager, FileManager, Retriever) and uses them
 * to create lightweight session-specific DeepLongMemory instances via the enhanced Builder API.
 * 
 * <p>When a factory-created DeepLongMemory instance is closed, only session-specific resources
 * (MemoryExtractor, session context) are cleaned up. Shared infrastructure resources
 * remain available for other sessions.
 */
public class DeepLongMemorySessionFactory {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final JVectorMemoryStore vectorStore;
    private final MemoryIndexManager indexManager;
    private final MarkdownFileManager fileManager;
    private final HybridRetriever retriever;
    private final MemoryProperties defaultProperties;
    private final MemoryExtractor memoryExtractor;

    public DeepLongMemorySessionFactory(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            JVectorMemoryStore vectorStore,
            MemoryIndexManager indexManager,
            MarkdownFileManager fileManager,
            HybridRetriever retriever,
            MemoryProperties defaultProperties,
            MemoryExtractor memoryExtractor) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager");
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.defaultProperties = Objects.requireNonNull(defaultProperties, "defaultProperties");
        this.memoryExtractor = Objects.requireNonNull(memoryExtractor, "memoryExtractor");
    }

    /**
     * Create a new DeepLongMemory instance for the given session.
     * 
     * @param sessionId unique session identifier (must not be blank)
     * @return configured DeepLongMemory instance sharing Spring-managed infrastructure
     * @throws IllegalArgumentException if sessionId is null or blank
     */
    public DeepLongMemory create(String sessionId) {
        return create(sessionId, defaultProperties);
    }

    /**
     * Create a new DeepLongMemory instance with custom properties.
     * 
     * @param sessionId unique session identifier (must not be blank)
     * @param customProperties session-specific properties (e.g., different topK)
     * @return configured DeepLongMemory instance sharing Spring-managed infrastructure
     * @throws IllegalArgumentException if sessionId is null or blank
     * @throws NullPointerException if customProperties is null
     */
    public DeepLongMemory create(String sessionId, MemoryProperties customProperties) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(customProperties, "customProperties");

        return DeepLongMemory.builder()
                .sessionId(sessionId)
                .properties(customProperties)
                .jdbcTemplate(jdbcTemplate)
                .transactionTemplate(transactionTemplate)
                .vectorStore(vectorStore)
                .indexManager(indexManager)
                .fileManager(fileManager)
                .retriever(retriever)
                .memoryExtractor(memoryExtractor)
                .skipInitialization(true)
                .build();
    }
}

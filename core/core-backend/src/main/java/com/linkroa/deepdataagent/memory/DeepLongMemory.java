package com.linkroa.deepdataagent.memory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.linkroa.deepdataagent.memory.config.MemoryIndexJdbcConfiguration;
import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.embedding.HashingMemoryEmbeddingModel;
import com.linkroa.deepdataagent.memory.extractor.SimpleMemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MarkdownChunker;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexRepository;
import com.linkroa.deepdataagent.memory.index.MemoryIndexSchemaInitializer;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.MemorySessionContext;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetrieverImpl;
import com.linkroa.deepdataagent.memory.retrieval.TemporalReranker;
import com.linkroa.deepdataagent.memory.vector.JVectorMemoryStore;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Session-bound long-term memory implementation for DeepDataAgent.
 */
public class DeepLongMemory implements LongTermMemory, AutoCloseable {

    private static final DateTimeFormatter AGENTSCOPE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final SimpleMemoryExtractor memoryExtractor;
    private final MarkdownFileManager fileManager;
    private final MemoryIndexManager indexManager;
    private final HybridRetriever retriever;
    private final MemoryProperties properties;
    private final MemorySessionContext sessionContext;
    private final List<AutoCloseable> ownedResources;

    public static Builder builder() {
        return new Builder();
    }

    DeepLongMemory(
            SimpleMemoryExtractor memoryExtractor,
            MarkdownFileManager fileManager,
            MemoryIndexManager indexManager,
            HybridRetriever retriever,
            MemoryProperties properties,
            MemorySessionContext sessionContext
    ) {
        this(memoryExtractor, fileManager, indexManager, retriever, properties, sessionContext, List.of());
    }

    private DeepLongMemory(
            SimpleMemoryExtractor memoryExtractor,
            MarkdownFileManager fileManager,
            MemoryIndexManager indexManager,
            HybridRetriever retriever,
            MemoryProperties properties,
            MemorySessionContext sessionContext,
            List<AutoCloseable> ownedResources
    ) {
        this.memoryExtractor = Objects.requireNonNull(memoryExtractor, "memoryExtractor");
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sessionContext = Objects.requireNonNull(sessionContext, "sessionContext");
        this.ownedResources = List.copyOf(ownedResources);
    }

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        return Mono.fromRunnable(() -> {
            if (msgs == null || msgs.size() < properties.getRecord().getMinRoundSize()) {
                return;
            }

            ConversationContext context = buildConversationContext(msgs);
            List<ExtractedMemory> memories = memoryExtractor.extractAndClassify(context);
            if (!properties.getRecord().isCaptureFullConversation()) {
                memories = memories.stream()
                        .filter(memory -> !"episodic".equals(memory.layer()))
                        .toList();
            }
            if (memories.isEmpty()) {
                return;
            }

            Set<String> changedPaths = fileManager.writeExtractedMemories(context, memories);
            if (properties.getIo().getCache().isFlushOnRecord()) {
                fileManager.flushDirtyFiles();
            }
            indexManager.syncIncremental(changedPaths);
        });
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromSupplier(() -> {
            String query = msg == null ? "" : msg.getTextContent();
            if (query == null || query.isBlank()) {
                return "";
            }

            RetrieveOptions options = new RetrieveOptions(
                    properties.getRetrieve().getTopK(),
                    properties.getRetrieve().getMaxChars(),
                    properties.getRetrieve().getRrfK(),
                    properties.getRetrieve().getMinScore(),
                    true
            );
            List<MemorySearchResult> results = retriever.hybridSearch(query, options);
            if (results.isEmpty()) {
                return "";
            }
            return formatResultsFromSource(results, options.maxChars());
        });
    }

    private String formatResultsFromSource(List<MemorySearchResult> results, int maxChars) {
        StringBuilder builder = new StringBuilder("## Related Memory\n\n");
        int remaining = Math.max(maxChars, 500);
        for (MemorySearchResult result : results) {
            if (remaining <= 0) {
                break;
            }
            String source = fileManager.readLines(result.filePath(), result.startLine(), result.endLine());
            if (source.isBlank()) {
                continue;
            }
            String header = "### [%s] Source: %s (lines %d-%d, score %.4f)\n"
                    .formatted(
                            result.memoryId(),
                            result.filePath(),
                            result.startLine(),
                            result.endLine(),
                            result.finalScore()
                    );
            String block = header + source + "\n\n";
            if (block.length() > remaining) {
                block = block.substring(0, Math.max(0, remaining - 3)) + "...";
            }
            builder.append(block);
            remaining -= block.length();
        }
        return builder.toString().strip();
    }

    private ConversationContext buildConversationContext(List<Msg> msgs) {
        List<ConversationMessage> messages = new ArrayList<>();
        Instant createdAt = sessionContext.createdAt();

        for (Msg msg : msgs) {
            if (msg == null) {
                continue;
            }
            Instant timestamp = parseInstant(msg.getTimestamp());
            if (timestamp != null && timestamp.isBefore(createdAt)) {
                createdAt = timestamp;
            }
            messages.add(new ConversationMessage(
                    msg.getName(),
                    roleOf(msg),
                    msg.getTextContent(),
                    msg.getTimestamp()
            ));
        }

        return new ConversationContext(
                sessionContext.sessionId(),
                createdAt,
                List.copyOf(messages)
        );
    }

    private static Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(timestamp, AGENTSCOPE_TIMESTAMP_FORMATTER)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static String roleOf(Msg msg) {
        return msg.getRole() == null ? "unknown" : msg.getRole().name().toLowerCase();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (AutoCloseable resource : ownedResources) {
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close memory resource", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Builder for creating DeepLongMemory instances.
     * 
     * <p>Supports three construction paths:
     * <ol>
     *   <li><b>Spring-managed infrastructure</b>: Provide dataSource, jdbcTemplate, and transactionTemplate</li>
     *   <li><b>ApplicationContext resolution</b>: Provide applicationContext to resolve beans automatically</li>
     *   <li><b>Default creation</b>: No dependencies provided, creates all infrastructure internally</li>
     * </ol>
     * 
     * <p>When using paths 1 or 2, the created DeepLongMemory instance will NOT close
     * shared infrastructure resources (DataSource, VectorStore, etc.) on close().
     * Only session-specific resources will be cleaned up.
     */
    public static class Builder {
        private String sessionId;
        private MemoryProperties properties = new MemoryProperties();
        
        // Spring 管理的基础设施
        private JdbcTemplate jdbcTemplate;
        private TransactionTemplate transactionTemplate;
        
        // 完全自定义组件
        private JVectorMemoryStore vectorStore;
        private MemoryIndexManager indexManager;
        private MarkdownFileManager fileManager;
        private HybridRetriever retriever;
        private SimpleMemoryExtractor memoryExtractor;
        private boolean skipInitialization = false;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder properties(MemoryProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
            return this;
        }

        public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            return this;
        }

        public Builder transactionTemplate(TransactionTemplate transactionTemplate) {
            this.transactionTemplate = transactionTemplate;
            return this;
        }

        public Builder vectorStore(JVectorMemoryStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder indexManager(MemoryIndexManager indexManager) {
            this.indexManager = indexManager;
            return this;
        }

        public Builder fileManager(MarkdownFileManager fileManager) {
            this.fileManager = fileManager;
            return this;
        }

        public Builder retriever(HybridRetriever retriever) {
            this.retriever = retriever;
            return this;
        }

        public Builder memoryExtractor(SimpleMemoryExtractor memoryExtractor) {
            this.memoryExtractor = memoryExtractor;
            return this;
        }

        /**
         * Skip initialization of file manager and index manager.
         * 
         * <p>Use this when the components are already initialized (e.g., Spring-managed beans).
         * This avoids redundant initialization and potential performance issues like
         * rebuilding indexes on every session creation.
         * 
         * @param skip true to skip initialization
         * @return this builder
         */
        public Builder skipInitialization(boolean skip) {
            this.skipInitialization = skip;
            return this;
        }

        private record Infrastructure(
                DataSource dataSource,
                JdbcTemplate jdbcTemplate,
                TransactionTemplate transactionTemplate,
                boolean springManaged
        ) {}

        private record Components(
                SimpleMemoryExtractor extractor,
                MarkdownFileManager fileManager,
                MemoryIndexManager indexManager,
                JVectorMemoryStore vectorStore,
                HybridRetriever retriever
        ) {}

        private Infrastructure resolveInfrastructure() {
            // 路径：提供了 Spring 基础设施
            if (jdbcTemplate != null && transactionTemplate != null) {
                // 使用 Spring 管理的基础设施，DataSource 不直接暴露
                return new Infrastructure(null, jdbcTemplate, transactionTemplate, true);
            }
            
            // 路径：创建默认基础设施（当前行为）
            MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
            DataSource ds = jdbcFactory.memoryIndexDataSource(properties);
            JdbcTemplate jt = jdbcFactory.memoryIndexJdbcTemplate(ds);
            PlatformTransactionManager transactionManager = jdbcFactory.memoryIndexTransactionManager(ds);
            TransactionTemplate tt = jdbcFactory.memoryIndexTransactionTemplate(transactionManager);
            return new Infrastructure(ds, jt, tt, false);
        }

        private Components resolveComponents(Infrastructure infra) {
            SimpleMemoryExtractor extractor = memoryExtractor != null ? memoryExtractor : new SimpleMemoryExtractor();
            MarkdownFileManager fm = fileManager != null ? fileManager : new MarkdownFileManager(properties);
            MarkdownChunker chunker = new MarkdownChunker(properties);
            MemoryIndexSchemaInitializer schemaInit = new MemoryIndexSchemaInitializer(infra.jdbcTemplate());
            MemoryIndexRepository repository = new MemoryIndexRepository(infra.jdbcTemplate());
            HashingMemoryEmbeddingModel embeddingModel = new HashingMemoryEmbeddingModel(properties);
            JVectorMemoryStore vs = vectorStore != null ? vectorStore : new JVectorMemoryStore(properties, embeddingModel);
            MemoryIndexManager im = indexManager != null ? indexManager : new MemoryIndexManager(
                    properties, fm, chunker, schemaInit, repository, infra.transactionTemplate(), vs);
            TemporalReranker temporalReranker = new TemporalReranker(properties);
            HybridRetriever r = retriever != null ? retriever : new HybridRetrieverImpl(im, temporalReranker);
            
            return new Components(extractor, fm, im, vs, r);
        }

        public DeepLongMemory build() {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            
            MemorySessionContext sessionContext = new MemorySessionContext(sessionId);
            Infrastructure infra = resolveInfrastructure();
            Components components = resolveComponents(infra);
            
            // 仅在未跳过时初始化
            if (!skipInitialization) {
                components.fileManager().initialize();
                components.indexManager().initialize();
            }
            
            List<AutoCloseable> resources = new ArrayList<>();
            
            // 仅当我们创建了 fileManager 时才跟踪
            if (fileManager == null) {
                resources.add(components.fileManager());
            }
            
            // 仅当我们创建了 vectorStore 时才跟踪
            if (vectorStore == null) {
                resources.add(components.vectorStore());
            }
            
            // 仅当基础设施不是 Spring 管理时才跟踪 DataSource
            if (!infra.springManaged() && infra.dataSource() instanceof AutoCloseable closeableDataSource) {
                resources.add(closeableDataSource);
            }
            
            return new DeepLongMemory(
                    components.extractor(),
                    components.fileManager(),
                    components.indexManager(),
                    components.retriever(),
                    properties,
                    sessionContext,
                    resources
            );
        }
    }
}

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
                sessionContext.userName(),
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

    public static class Builder {
        private String sessionId;
        private String userName;
        private MemoryProperties properties = new MemoryProperties();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userName(String userName) {
            this.userName = userName;
            return this;
        }

        public Builder properties(MemoryProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
            return this;
        }

        public DeepLongMemory build() {
            MemorySessionContext sessionContext = new MemorySessionContext(sessionId, userName, null);
            MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
            DataSource dataSource = jdbcFactory.memoryIndexDataSource(properties);
            JdbcTemplate jdbcTemplate = jdbcFactory.memoryIndexJdbcTemplate(dataSource);
            PlatformTransactionManager transactionManager = jdbcFactory.memoryIndexTransactionManager(dataSource);
            TransactionTemplate transactionTemplate = jdbcFactory.memoryIndexTransactionTemplate(transactionManager);

            SimpleMemoryExtractor memoryExtractor = new SimpleMemoryExtractor();
            MarkdownFileManager fileManager = new MarkdownFileManager(properties);
            MarkdownChunker chunker = new MarkdownChunker(properties);
            MemoryIndexSchemaInitializer schemaInitializer = new MemoryIndexSchemaInitializer(jdbcTemplate);
            MemoryIndexRepository repository = new MemoryIndexRepository(jdbcTemplate);
            HashingMemoryEmbeddingModel embeddingModel = new HashingMemoryEmbeddingModel(properties);
            JVectorMemoryStore vectorStore = new JVectorMemoryStore(properties, embeddingModel);
            MemoryIndexManager indexManager = new MemoryIndexManager(
                    properties,
                    fileManager,
                    chunker,
                    schemaInitializer,
                    repository,
                    transactionTemplate,
                    vectorStore
            );
            TemporalReranker temporalReranker = new TemporalReranker(properties);
            HybridRetriever retriever = new HybridRetrieverImpl(indexManager, temporalReranker);

            fileManager.initialize();
            indexManager.initialize();

            List<AutoCloseable> resources = new ArrayList<>();
            resources.add(fileManager);
            resources.add(vectorStore);
            if (dataSource instanceof AutoCloseable closeableDataSource) {
                resources.add(closeableDataSource);
            }

            return new DeepLongMemory(
                    memoryExtractor,
                    fileManager,
                    indexManager,
                    retriever,
                    properties,
                    sessionContext,
                    resources
            );
        }
    }
}

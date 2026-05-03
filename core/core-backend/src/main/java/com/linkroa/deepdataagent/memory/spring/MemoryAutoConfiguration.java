package com.linkroa.deepdataagent.memory.spring;

import javax.sql.DataSource;

import com.linkroa.deepdataagent.memory.DeepLongMemory;
import com.linkroa.deepdataagent.memory.config.MemoryIndexJdbcConfiguration;
import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.embedding.HashingMemoryEmbeddingModel;
import com.linkroa.deepdataagent.memory.extractor.FallbackMemoryExtractor;
import com.linkroa.deepdataagent.memory.extractor.LLMMemoryExtractor;
import com.linkroa.deepdataagent.memory.extractor.MemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MarkdownChunker;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexRepository;
import com.linkroa.deepdataagent.memory.index.MemoryIndexSchemaInitializer;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetrieverImpl;
import com.linkroa.deepdataagent.memory.retrieval.TemporalReranker;
import com.linkroa.deepdataagent.memory.vector.JVectorMemoryStore;

import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Boot auto-configuration for DeepLongMemory infrastructure.
 * 
 * <p>Manages shared resources (DataSource, JdbcTemplate, VectorStore, IndexManager, etc.)
 * as Spring beans, enabling multiple session-scoped DeepLongMemory instances to share
 * the same infrastructure without duplicating connection pools and indexes.
 */
@Configuration
@ConditionalOnClass(DeepLongMemory.class)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    @Bean(name = MemoryIndexJdbcConfiguration.DATA_SOURCE_BEAN)
    @ConditionalOnMissingBean(name = MemoryIndexJdbcConfiguration.DATA_SOURCE_BEAN)
    public DataSource memoryDataSource(MemoryProperties properties) {
        MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
        return jdbcFactory.memoryIndexDataSource(properties);
    }

    @Bean(name = MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN)
    @ConditionalOnMissingBean(name = MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN)
    public JdbcTemplate memoryJdbcTemplate(@Qualifier(MemoryIndexJdbcConfiguration.DATA_SOURCE_BEAN) DataSource dataSource) {
        MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
        return jdbcFactory.memoryIndexJdbcTemplate(dataSource);
    }

    @Bean(name = MemoryIndexJdbcConfiguration.TRANSACTION_MANAGER_BEAN)
    @ConditionalOnMissingBean(name = MemoryIndexJdbcConfiguration.TRANSACTION_MANAGER_BEAN)
    public PlatformTransactionManager memoryTransactionManager(@Qualifier(MemoryIndexJdbcConfiguration.DATA_SOURCE_BEAN) DataSource dataSource) {
        MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
        return jdbcFactory.memoryIndexTransactionManager(dataSource);
    }

    @Bean(name = MemoryIndexJdbcConfiguration.TRANSACTION_TEMPLATE_BEAN)
    @ConditionalOnMissingBean(name = MemoryIndexJdbcConfiguration.TRANSACTION_TEMPLATE_BEAN)
    public TransactionTemplate memoryTransactionTemplate(
            @Qualifier(MemoryIndexJdbcConfiguration.TRANSACTION_MANAGER_BEAN) PlatformTransactionManager transactionManager) {
        MemoryIndexJdbcConfiguration jdbcFactory = new MemoryIndexJdbcConfiguration();
        return jdbcFactory.memoryIndexTransactionTemplate(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public HashingMemoryEmbeddingModel memoryEmbeddingModel(MemoryProperties properties) {
        return new HashingMemoryEmbeddingModel(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownChunker markdownChunker(MemoryProperties properties) {
        return new MarkdownChunker(properties);
    }

    @Bean(name = "memorySchemaInitializer")
    @ConditionalOnMissingBean(name = "memorySchemaInitializer")
    public MemoryIndexSchemaInitializer schemaInitializer(@Qualifier(MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN) JdbcTemplate jdbcTemplate) {
        return new MemoryIndexSchemaInitializer(jdbcTemplate);
    }

    @Bean(name = "memoryIndexRepository")
    @ConditionalOnMissingBean(name = "memoryIndexRepository")
    public MemoryIndexRepository indexRepository(@Qualifier(MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN) JdbcTemplate jdbcTemplate) {
        return new MemoryIndexRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemporalReranker temporalReranker(MemoryProperties properties) {
        return new TemporalReranker(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JVectorMemoryStore vectorStore(MemoryProperties properties, HashingMemoryEmbeddingModel embeddingModel) {
        return new JVectorMemoryStore(properties, embeddingModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownFileManager fileManager(MemoryProperties properties) {
        MarkdownFileManager manager = new MarkdownFileManager(properties);
        manager.initialize();
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryIndexManager indexManager(
            MemoryProperties properties,
            MarkdownFileManager fileManager,
            MarkdownChunker chunker,
            MemoryIndexSchemaInitializer schemaInitializer,
            MemoryIndexRepository repository,
            @Qualifier(MemoryIndexJdbcConfiguration.TRANSACTION_TEMPLATE_BEAN) TransactionTemplate transactionTemplate,
            JVectorMemoryStore vectorStore) {
        MemoryIndexManager manager = new MemoryIndexManager(
                properties, fileManager, chunker, schemaInitializer, repository, transactionTemplate, vectorStore);
        manager.initialize();
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public HybridRetriever hybridRetriever(MemoryIndexManager indexManager, TemporalReranker temporalReranker) {
        return new HybridRetrieverImpl(indexManager, temporalReranker);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeepLongMemorySessionFactory memorySessionFactory(
            @Qualifier(MemoryIndexJdbcConfiguration.JDBC_TEMPLATE_BEAN) JdbcTemplate jdbcTemplate,
            @Qualifier(MemoryIndexJdbcConfiguration.TRANSACTION_TEMPLATE_BEAN) TransactionTemplate transactionTemplate,
            JVectorMemoryStore vectorStore,
            MemoryIndexManager indexManager,
            MarkdownFileManager fileManager,
            HybridRetriever retriever,
            MemoryProperties properties,
            MemoryExtractor memoryExtractor) {
        return new DeepLongMemorySessionFactory(
                jdbcTemplate, transactionTemplate, vectorStore, 
                indexManager, fileManager, retriever, properties, memoryExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.memory.extractor.type", havingValue = "llm", matchIfMissing = true)
    public MemoryExtractor llmMemoryExtractor(MemoryProperties properties) {
        ChatModel chatModel = createChatModel(properties);
        return new LLMMemoryExtractor(chatModel);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.memory.extractor.type", havingValue = "fallback")
    public MemoryExtractor fallbackMemoryExtractor() {
        return new FallbackMemoryExtractor();
    }

    private ChatModelBase createChatModel(MemoryProperties properties) {
        String provider = properties.getExtractor().getLlmProvider();
        String modelName = properties.getExtractor().getLlmModelName();
        double temperature = properties.getExtractor().getLlmTemperature();

        return switch (provider.toLowerCase()) {
            case "openai" -> OpenAIChatModel.builder()
                    .modelName(modelName)
                    .temperature(temperature)
                    .build();
            default -> DashScopeChatModel.builder()
                    .modelName(modelName)
                    .temperature(temperature)
                    .build();
        };
    }
}

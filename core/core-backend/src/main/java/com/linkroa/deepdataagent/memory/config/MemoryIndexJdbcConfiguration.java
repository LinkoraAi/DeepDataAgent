package com.linkroa.deepdataagent.memory.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring JDBC infrastructure dedicated to the memory SQLite index.
 *
 * <p>The application may use a different database later, so these beans are named
 * explicitly and consumed only by the memory index components.</p>
 */
@Configuration
public class MemoryIndexJdbcConfiguration {

    public static final String DATA_SOURCE_BEAN = "memoryIndexDataSource";
    public static final String JDBC_TEMPLATE_BEAN = "memoryIndexJdbcTemplate";
    public static final String TRANSACTION_MANAGER_BEAN = "memoryIndexTransactionManager";
    public static final String TRANSACTION_TEMPLATE_BEAN = "memoryIndexTransactionTemplate";

    @Bean(name = DATA_SOURCE_BEAN)
    public DataSource memoryIndexDataSource(MemoryProperties properties) {
        Path databasePath = databasePath(properties);
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create memory index directory: " + databasePath.getParent(), e);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("memory-index-sqlite");
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        return new HikariDataSource(config);
    }

    @Bean(name = JDBC_TEMPLATE_BEAN)
    public JdbcTemplate memoryIndexJdbcTemplate(@Qualifier(DATA_SOURCE_BEAN) DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = TRANSACTION_MANAGER_BEAN)
    public PlatformTransactionManager memoryIndexTransactionManager(
            @Qualifier(DATA_SOURCE_BEAN) DataSource dataSource
    ) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = TRANSACTION_TEMPLATE_BEAN)
    public TransactionTemplate memoryIndexTransactionTemplate(
            @Qualifier(TRANSACTION_MANAGER_BEAN) PlatformTransactionManager transactionManager
    ) {
        return new TransactionTemplate(transactionManager);
    }

    private static Path databasePath(MemoryProperties properties) {
        String configured = properties.getIndex().getDbPath();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(properties.getRootPath()).resolve(".index").resolve("memory.db").toAbsolutePath().normalize();
    }
}

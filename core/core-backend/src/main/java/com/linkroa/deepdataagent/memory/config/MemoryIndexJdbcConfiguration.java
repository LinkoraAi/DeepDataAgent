package com.linkroa.deepdataagent.memory.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Plain Java factory for the memory SQLite index infrastructure.
 *
 * <p>The application may use a different database later, so these factory methods are
 * kept isolated and consumed only by the memory index components.</p>
 */
public class MemoryIndexJdbcConfiguration {

    public static final String DATA_SOURCE_BEAN = "memoryIndexDataSource";
    public static final String JDBC_TEMPLATE_BEAN = "memoryIndexJdbcTemplate";
    public static final String TRANSACTION_MANAGER_BEAN = "memoryIndexTransactionManager";
    public static final String TRANSACTION_TEMPLATE_BEAN = "memoryIndexTransactionTemplate";

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

    public JdbcTemplate memoryIndexJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    public PlatformTransactionManager memoryIndexTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    public TransactionTemplate memoryIndexTransactionTemplate(
            PlatformTransactionManager transactionManager
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

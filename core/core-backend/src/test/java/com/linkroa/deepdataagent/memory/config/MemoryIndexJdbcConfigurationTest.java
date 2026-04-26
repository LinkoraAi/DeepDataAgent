package com.linkroa.deepdataagent.memory.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MemoryIndexJdbcConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void should_createJdbcInfrastructure_when_beanMethodsInvoked_given_explicitDatabasePath() {
        // given
        MemoryProperties properties = new MemoryProperties();
        Path databasePath = tempDir.resolve("index").resolve("memory.db");
        properties.getIndex().setDbPath(databasePath.toString());
        MemoryIndexJdbcConfiguration configuration = new MemoryIndexJdbcConfiguration();

        // when
        DataSource dataSource = configuration.memoryIndexDataSource(properties);
        JdbcTemplate jdbcTemplate = configuration.memoryIndexJdbcTemplate(dataSource);
        PlatformTransactionManager transactionManager = configuration.memoryIndexTransactionManager(dataSource);
        TransactionTemplate transactionTemplate = configuration.memoryIndexTransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS smoke(id TEXT)"));

        // then
        assertTrue(Files.exists(databasePath.getParent()));
        assertNotNull(jdbcTemplate);
        assertNotNull(transactionManager);
        assertNotNull(transactionTemplate);

        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }

    @Test
    void should_createDefaultDatabaseDirectory_when_dataSourceCreated_given_rootPathOnly() {
        // given
        MemoryProperties properties = new MemoryProperties();
        properties.setRootPath(tempDir.resolve("memory-root").toString());
        MemoryIndexJdbcConfiguration configuration = new MemoryIndexJdbcConfiguration();

        // when
        DataSource dataSource = configuration.memoryIndexDataSource(properties);

        // then
        assertTrue(Files.exists(tempDir.resolve("memory-root").resolve(".index")));

        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }
}

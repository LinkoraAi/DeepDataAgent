package com.linkroa.deepdataagent.datasource.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatasourceSchemaInitializerTest {

    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DatasourceSchemaInitializer schemaInitializer;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("datasource-schema-test-");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(0);
        jdbcTemplate = new JdbcTemplate(dataSource);
        schemaInitializer = new DatasourceSchemaInitializer(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void should_createAllTablesAndIndexes_when_initialize_given_freshDatabase() {
        schemaInitializer.initialize();

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('datasource_connection', 'database_schema', 'table_info', 'column_info', 'api_schema', 'api_field')",
                Integer.class
        );
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'uk_%'",
                Integer.class
        );

        assertNotNull(tableCount);
        assertNotNull(indexCount);
        assertEquals(6, tableCount);
        assertEquals(6, indexCount);
    }

    @Test
    void should_createColumnInfoTableWithDescriptionColumn_when_initialize_given_freshDatabase() {
        schemaInitializer.initialize();

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('column_info') WHERE name IN ('id', 'table_id', 'column_name', 'data_type', 'column_comment', 'column_custom_comment', 'created_at', 'updated_at', 'is_deleted')",
                Integer.class
        );

        assertNotNull(columnCount);
        assertEquals(9, columnCount);
    }

    @Test
    void should_createApiSchemaWithUrlAndConfig_when_initialize_given_freshDatabase() {
        schemaInitializer.initialize();

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('api_schema') WHERE name IN ('id', 'connection_id', 'name', 'url', 'method', 'config', 'created_at', 'updated_at', 'created_by', 'updated_by', 'is_deleted')",
                Integer.class
        );

        assertNotNull(columnCount);
        assertEquals(11, columnCount);
    }

    @Test
    void should_notCreateApiDataTable_when_initialize_given_freshDatabase() {
        schemaInitializer.initialize();

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = 'api_data_table'",
                Integer.class
        );

        assertNotNull(tableCount);
        assertEquals(0, tableCount);
    }

    @Test
    void should_createDatasourceConnectionWithoutApiConfig_when_initialize_given_freshDatabase() {
        schemaInitializer.initialize();

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('datasource_connection') WHERE name IN ('id', 'name', 'type', 'sub_type', 'status', 'jdbc_connection_config', 'description', 'created_at', 'updated_at', 'created_by', 'updated_by', 'is_deleted')",
                Integer.class
        );

        assertNotNull(columnCount);
        assertEquals(12, columnCount);
    }
}

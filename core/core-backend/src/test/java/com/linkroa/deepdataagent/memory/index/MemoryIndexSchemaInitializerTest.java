package com.linkroa.deepdataagent.memory.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class MemoryIndexSchemaInitializerTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // given
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void should_createSchemaAndReturnTrue_when_initializeSchema_given_sqliteJdbcTemplate() {
        // given
        MemoryIndexSchemaInitializer initializer = new MemoryIndexSchemaInitializer(jdbcTemplate);

        // when
        boolean ftsAvailable = initializer.initializeSchema();
        int filesTableCount = objectCount("table", "files");
        int chunksTableCount = objectCount("table", "chunks");
        int ftsTableCount = objectCount("table", "chunks_fts");
        int chunkFileIndexCount = objectCount("index", "idx_chunks_file");

        // then
        assertTrue(ftsAvailable);
        assertEquals(1, filesTableCount);
        assertEquals(1, chunksTableCount);
        assertEquals(1, ftsTableCount);
        assertEquals(1, chunkFileIndexCount);
    }

    @Test
    void should_returnFalse_when_initializeSchema_given_ftsCreationFails() {
        // given
        JdbcTemplate mockedJdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("CREATE VIRTUAL TABLE")) {
                throw new DataAccessResourceFailureException("fts unavailable");
            }
            return null;
        })
                .when(mockedJdbcTemplate)
                .execute(anyString());
        MemoryIndexSchemaInitializer initializer = new MemoryIndexSchemaInitializer(mockedJdbcTemplate);

        // when
        boolean ftsAvailable = initializer.initializeSchema();

        // then
        assertFalse(ftsAvailable);
    }

    private int objectCount(String type, String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
                Integer.class,
                type,
                name
        );
        return count == null ? 0 : count;
    }
}

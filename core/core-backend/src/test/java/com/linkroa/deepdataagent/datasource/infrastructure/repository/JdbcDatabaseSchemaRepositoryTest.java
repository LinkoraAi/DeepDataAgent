package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatabaseSchemaRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcDatabaseSchemaRepository repository;

    @Test
    void should_saveAndReturnSchema_when_save_given_validSchema() {
        DatabaseSchema saved = repository.save(new DatabaseSchema(null, 1L, "testdb", "test description", null, null));

        assertNotNull(saved.id());
        assertEquals("testdb", saved.schemaName());
        assertEquals(1L, saved.connectionId());
    }

    @Test
    void should_findById_when_saved_given_existingId() {
        DatabaseSchema saved = repository.save(new DatabaseSchema(null, 1L, "testdb", null, null, null));

        Optional<DatabaseSchema> found = repository.findById(saved.id());

        assertTrue(found.isPresent());
        assertEquals("testdb", found.get().schemaName());
    }

    @Test
    void should_findByConnectionId_when_saved_given_connectionId() {
        repository.save(new DatabaseSchema(null, 1L, "db1", null, null, null));
        repository.save(new DatabaseSchema(null, 1L, "db2", null, null, null));
        repository.save(new DatabaseSchema(null, 2L, "db3", null, null, null));

        List<DatabaseSchema> results = repository.findByConnectionId(1L);

        assertEquals(2, results.size());
    }

    @Test
    void should_findByConnectionIdAndSchemaName_when_saved_given_connectionIdAndName() {
        repository.save(new DatabaseSchema(null, 1L, "testdb", null, null, null));

        Optional<DatabaseSchema> found = repository.findByConnectionIdAndSchemaName(1L, "testdb");
        assertTrue(found.isPresent());

        Optional<DatabaseSchema> notFound = repository.findByConnectionIdAndSchemaName(1L, "otherdb");
        assertTrue(notFound.isEmpty());
    }

    @Test
    void should_updateSchema_when_update_given_existingSchema() {
        DatabaseSchema saved = repository.save(new DatabaseSchema(null, 1L, "testdb", null, null, null));
        DatabaseSchema updated = new DatabaseSchema(saved.id(), 1L, "renameddb", "new desc", saved.createdAt(), saved.updatedAt());

        DatabaseSchema result = repository.update(updated);

        assertEquals("renameddb", result.schemaName());
        assertEquals("new desc", result.description());
    }

    @Test
    void should_softDelete_when_softDeleteByConnectionId_given_connectionId() {
        repository.save(new DatabaseSchema(null, 1L, "testdb", null, null, null));

        repository.softDeleteByConnectionId(1L);

        List<DatabaseSchema> results = repository.findByConnectionId(1L);
        assertEquals(0, results.size());
    }

    @Test
    void should_hardDelete_when_deleteByConnectionId_given_connectionId() {
        repository.save(new DatabaseSchema(null, 1L, "testdb", null, null, null));

        repository.deleteByConnectionId(1L);

        List<DatabaseSchema> results = repository.findByConnectionId(1L);
        assertEquals(0, results.size());
    }
}

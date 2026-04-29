package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcApiSchemaRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcApiSchemaRepository repository;

    @Test
    void should_saveAndReturnSchema_when_save_given_validApiSchema() {
        ApiSchema apiSchema = new ApiSchema(null, 1L, "users-api", "/api/users", HttpMethod.GET, "$.data", null, null, null, null);

        ApiSchema saved = repository.save(apiSchema);

        assertNotNull(saved.id());
        assertEquals("users-api", saved.name());
        assertEquals("/api/users", saved.path());
        assertEquals(HttpMethod.GET, saved.method());
    }

    @Test
    void should_findById_when_saved_given_id() {
        ApiSchema saved = repository.save(new ApiSchema(null, 1L, "test-api", "/api/test", HttpMethod.POST, "$.result", null, null, null, null));

        Optional<ApiSchema> found = repository.findById(saved.id());

        assertTrue(found.isPresent());
        assertEquals("test-api", found.get().name());
    }

    @Test
    void should_returnEmpty_when_findById_given_nonExistentId() {
        Optional<ApiSchema> found = repository.findById(999L);
        assertTrue(found.isEmpty());
    }

    @Test
    void should_findByConnectionId_when_saved_given_connectionId() {
        repository.save(new ApiSchema(null, 1L, "api1", "/api/1", HttpMethod.GET, null, null, null, null, null));
        repository.save(new ApiSchema(null, 1L, "api2", "/api/2", HttpMethod.POST, null, null, null, null, null));
        repository.save(new ApiSchema(null, 2L, "api3", "/api/3", HttpMethod.GET, null, null, null, null, null));

        List<ApiSchema> results = repository.findByConnectionId(1L);
        assertEquals(2, results.size());
    }

    @Test
    void should_updateSchema_when_update_given_existingSchema() {
        ApiSchema saved = repository.save(new ApiSchema(null, 1L, "old-name", "/old", HttpMethod.GET, null, null, null, null, null));

        ApiSchema updated = new ApiSchema(saved.id(), 1L, "new-name", "/new", HttpMethod.POST, "$.data", saved.createdAt(), saved.updatedAt(), saved.createdBy(), saved.updatedBy());
        repository.update(updated);

        Optional<ApiSchema> found = repository.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("new-name", found.get().name());
        assertEquals("/new", found.get().path());
        assertEquals(HttpMethod.POST, found.get().method());
    }

    @Test
    void should_softDeleteByConnectionId_when_deleteByConnectionId_given_connectionId() {
        repository.save(new ApiSchema(null, 1L, "api1", "/api/1", HttpMethod.GET, null, null, null, null, null));
        repository.save(new ApiSchema(null, 1L, "api2", "/api/2", HttpMethod.POST, null, null, null, null, null));

        repository.deleteByConnectionId(1L);

        List<ApiSchema> results = repository.findByConnectionId(1L);
        assertEquals(0, results.size());
    }

    @Test
    void should_handleNullMethod_when_save_given_nullMethod() {
        ApiSchema apiSchema = new ApiSchema(null, 1L, "test", "/api/test", null, null, null, null, null, null);

        ApiSchema saved = repository.save(apiSchema);

        assertNotNull(saved.id());
        assertNull(saved.method());
    }
}

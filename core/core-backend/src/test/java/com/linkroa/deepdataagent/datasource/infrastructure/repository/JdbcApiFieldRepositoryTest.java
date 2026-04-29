package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcApiFieldRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcApiFieldRepository repository;

    @Test
    void should_saveAndReturnField_when_save_given_validApiField() {
        ApiField apiField = new ApiField(null, 1L, "user_id", "userId", "$.id", "INTEGER", "user id", null, null);

        ApiField saved = repository.save(apiField);

        assertNotNull(saved.id());
        assertEquals("user_id", saved.originalName());
        assertEquals("userId", saved.displayName());
        assertEquals("$.id", saved.jsonPath());
        assertEquals("INTEGER", saved.fieldType());
    }

    @Test
    void should_findByApiSchemaId_when_saved_given_schemaId() {
        repository.save(new ApiField(null, 1L, "id", "id", "$.id", "INTEGER", null, null, null));
        repository.save(new ApiField(null, 1L, "name", "name", "$.name", "STRING", null, null, null));
        repository.save(new ApiField(null, 2L, "price", "price", "$.price", "DOUBLE", null, null, null));

        List<ApiField> results = repository.findByApiSchemaId(1L);
        assertEquals(2, results.size());
    }

    @Test
    void should_updateField_when_update_given_existingField() {
        ApiField saved = repository.save(new ApiField(null, 1L, "id", "id", "$.id", "INTEGER", null, null, null));

        ApiField updated = new ApiField(saved.id(), 1L, "id", "userId", "$.user.id", "BIGINT", "user id", saved.createdAt(), saved.updatedAt());
        ApiField result = repository.update(updated);

        assertEquals("userId", result.displayName());
        assertEquals("$.user.id", result.jsonPath());
        assertEquals("BIGINT", result.fieldType());
    }

    @Test
    void should_softDeleteByApiSchemaId_when_deleteByApiSchemaId_given_schemaId() {
        repository.save(new ApiField(null, 1L, "id", "id", "$.id", "INTEGER", null, null, null));
        repository.save(new ApiField(null, 1L, "name", "name", "$.name", "STRING", null, null, null));

        repository.deleteByApiSchemaId(1L);

        List<ApiField> results = repository.findByApiSchemaId(1L);
        assertEquals(0, results.size());
    }

    @Test
    void should_returnEmptyList_when_noFieldsFound_given_nonExistentSchemaId() {
        List<ApiField> results = repository.findByApiSchemaId(999L);
        assertTrue(results.isEmpty());
    }
}

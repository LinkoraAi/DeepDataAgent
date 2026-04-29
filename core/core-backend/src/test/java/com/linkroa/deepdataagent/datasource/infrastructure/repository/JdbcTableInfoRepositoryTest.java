package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTableInfoRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcTableInfoRepository repository;

    @Test
    void should_saveTableInfo_when_save_given_validTableInfo() {
        TableInfo saved = repository.save(new TableInfo(null, 1L, "users", "system comment", null, null, null));

        assertNotNull(saved.id());
        assertEquals("system comment", saved.tableComment());
    }

    @Test
    void should_updateTableComment_when_updateTableComment_given_existingTable() {
        TableInfo saved = repository.save(new TableInfo(null, 1L, "users", "old system", null, null, null));

        repository.updateTableCustomComment(saved.id(), "new system");

        Optional<TableInfo> found = repository.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("new system", found.get().tableCustomComment());
    }

    @Test
    void should_findByDatabaseSchemaIdAndKeyword_when_saved_given_matchingKeyword() {
        repository.save(new TableInfo(null, 1L, "users", null, null, null, null));
        repository.save(new TableInfo(null, 1L, "user_roles", null, null, null, null));
        repository.save(new TableInfo(null, 1L, "orders", null, null, null, null));

        List<TableInfo> results = repository.findByDatabaseSchemaIdAndKeyword(1L, "user", 0, 10);

        assertEquals(2, results.size());
        assertEquals(2, repository.countByDatabaseSchemaIdAndKeyword(1L, "user"));
    }
}

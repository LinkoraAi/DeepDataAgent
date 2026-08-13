package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JdbcColumnInfoRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcColumnInfoRepository repository;

    @Test
    void should_saveColumnInfo_when_save_given_validColumnInfo() {
        ColumnInfo saved = repository.save(new ColumnInfo(null, 1L, "id", "INTEGER", "column description", null, null, null));

        assertNotNull(saved.id());
        assertEquals("column description", saved.columnComment());
    }

    @Test
    void should_updateColumnDescription_when_updateDescription_given_existingColumn() {
        ColumnInfo saved = repository.save(new ColumnInfo(null, 1L, "id", "INTEGER", "old description", null, null, null));

        repository.updateColumnCustomComment(saved.id(), "new description");

        List<ColumnInfo> results = repository.findByTableId(1L);
        assertEquals(1, results.size());
        assertEquals("new description", results.getFirst().columnCustomComment());
    }

    @Test
    void should_updateColumnTypeAndDescription_when_update_given_changedColumnInfo() {
        ColumnInfo saved = repository.save(new ColumnInfo(null, 1L, "id", "INTEGER", "old description", null, null, null));

        repository.update(new ColumnInfo(saved.id(), 1L, "id", "BIGINT", "new description", null, saved.createdAt(), saved.updatedAt()));

        List<ColumnInfo> results = repository.findByTableId(1L);
        assertEquals(1, results.size());
        assertEquals("BIGINT", results.getFirst().dataType());
        assertEquals("new description", results.getFirst().columnComment());
    }
}

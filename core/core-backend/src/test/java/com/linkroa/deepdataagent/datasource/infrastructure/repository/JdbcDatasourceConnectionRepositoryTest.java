package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatasourceConnectionRepositoryTest extends DatasourceRepositoryTestSupport {

    @Autowired
    private JdbcDatasourceConnectionRepository repository;

    @Test
    void should_saveAndReturnConnection_when_save_given_validJdbcConnection() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        assertNotNull(saved.id());
        assertEquals("test-ds", saved.name());
        assertEquals(DatasourceType.JDBC, saved.type());
        assertEquals(JdbcType.MYSQL, saved.subType());
        assertEquals(DatasourceStatus.ENABLED, saved.status());
        assertNotNull(saved.jdbcConnectionConfig());
        assertEquals("localhost", saved.jdbcConnectionConfig().host());
    }

    @Test
    void should_saveApiConnection_when_save_given_validApiConnection() {
        DatasourceConnection saved = repository.save(createApiConnection(null));

        assertNotNull(saved.id());
        assertEquals(DatasourceType.API, saved.type());
        assertNotNull(saved.apiConnectionConfig());
        assertEquals("http://example.com", saved.apiConnectionConfig().url());
    }

    @Test
    void should_findById_when_saved_given_existingId() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        Optional<DatasourceConnection> found = repository.findById(saved.id());

        assertTrue(found.isPresent());
        assertEquals(saved.name(), found.get().name());
    }

    @Test
    void should_returnEmpty_when_findById_given_nonExistentId() {
        Optional<DatasourceConnection> found = repository.findById(999L);
        assertTrue(found.isEmpty());
    }

    @Test
    void should_findByName_when_saved_given_existingName() {
        repository.save(createJdbcConnection(null));

        Optional<DatasourceConnection> found = repository.findByName("test-ds");

        assertTrue(found.isPresent());
        assertEquals("test-ds", found.get().name());
    }

    @Test
    void should_returnEmpty_when_findByName_given_nonExistentName() {
        Optional<DatasourceConnection> found = repository.findByName("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void should_returnAll_when_findAll_given_multipleConnections() {
        repository.save(createJdbcConnection(null));
        repository.save(createApiConnection(null));

        List<DatasourceConnection> all = repository.findAll();

        assertEquals(2, all.size());
    }

    @Test
    void should_updateConnection_when_update_given_existingConnection() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));
        DatasourceConnection updated = new DatasourceConnection(
                saved.id(), "updated-name", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("newhost", 3307, "newdb", "newuser", "newpass"),
                null, null, "updated desc", saved.createdAt(), saved.updatedAt(), saved.createdBy(), saved.updatedBy()
        );

        DatasourceConnection result = repository.update(updated);

        assertEquals("updated-name", result.name());
        assertEquals("newhost", result.jdbcConnectionConfig().host());
    }

    @Test
    void should_updateStatus_when_updateStatus_given_existingId() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        repository.updateStatus(saved.id(), DatasourceStatus.DISABLED);

        Optional<DatasourceConnection> found = repository.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals(DatasourceStatus.DISABLED, found.get().status());
    }

    @Test
    void should_softDelete_when_deleteById_given_existingId() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        repository.deleteById(saved.id());

        Optional<DatasourceConnection> found = repository.findById(saved.id());
        assertTrue(found.isEmpty());
    }

    @Test
    void should_filterByKeyword_when_findByCondition_given_keyword() {
        repository.save(createJdbcConnection(null));

        List<DatasourceConnection> results = repository.findByCondition("test", null, null, 0, 10);
        assertEquals(1, results.size());

        List<DatasourceConnection> empty = repository.findByCondition("nonexistent", null, null, 0, 10);
        assertEquals(0, empty.size());
    }

    @Test
    void should_filterByType_when_findByCondition_given_type() {
        repository.save(createJdbcConnection(null));
        repository.save(createApiConnection(null));

        List<DatasourceConnection> jdbcResults = repository.findByCondition(null, DatasourceType.JDBC, null, 0, 10);
        assertEquals(1, jdbcResults.size());
        assertEquals(DatasourceType.JDBC, jdbcResults.getFirst().type());

        List<DatasourceConnection> apiResults = repository.findByCondition(null, DatasourceType.API, null, 0, 10);
        assertEquals(1, apiResults.size());
        assertEquals(DatasourceType.API, apiResults.getFirst().type());
    }

    @Test
    void should_filterByStatus_when_findByCondition_given_status() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));
        repository.updateStatus(saved.id(), DatasourceStatus.DISABLED);

        List<DatasourceConnection> enabled = repository.findByCondition(null, null, DatasourceStatus.ENABLED, 0, 10);
        assertEquals(0, enabled.size());

        List<DatasourceConnection> disabled = repository.findByCondition(null, null, DatasourceStatus.DISABLED, 0, 10);
        assertEquals(1, disabled.size());
    }

    @Test
    void should_countByCondition_when_countByCondition_given_filters() {
        repository.save(createJdbcConnection(null));
        repository.save(createApiConnection(null));

        long total = repository.countByCondition(null, null, null);
        assertEquals(2, total);

        long jdbcCount = repository.countByCondition(null, DatasourceType.JDBC, null);
        assertEquals(1, jdbcCount);
    }

    @Test
    void should_paginate_when_findByCondition_given_pageAndSize() {
        for (int i = 0; i < 5; i++) {
            DatasourceConnection conn = new DatasourceConnection(
                    null, "ds-" + i, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                    new JdbcConnectionConfig("host", 3306, "db", "user", "pass"),
                    null, null, null, null, null, null, null
            );
            repository.save(conn);
        }

        List<DatasourceConnection> page1 = repository.findByCondition(null, null, null, 1, 2);
        assertEquals(2, page1.size());

        List<DatasourceConnection> page3 = repository.findByCondition(null, null, null, 3, 2);
        assertEquals(1, page3.size());
    }

    private DatasourceConnection createJdbcConnection(Long id) {
        return new DatasourceConnection(id, "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass"),
                null, null, null, null, null, null, null);
    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null);
    }
}

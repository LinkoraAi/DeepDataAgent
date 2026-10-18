package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatasourceConnectionRepositoryTest extends DatasourceRepositoryTestSupport {

    /** 测试用归属用户 ID（所有落库数据源均归属该用户）。 */
    private static final Long OWNER_ID = 1L;

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
                new JdbcConnectionConfig("newhost", 3307, "newdb", "newuser", "newpass", null),
                "updated desc", saved.ownerId(), saved.createdAt(), saved.updatedAt(), saved.createdBy(), saved.updatedBy()
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

        List<DatasourceConnection> results = repository.findByCondition("test", null, null, 0, 10, OWNER_ID);
        assertEquals(1, results.size());

        List<DatasourceConnection> empty = repository.findByCondition("nonexistent", null, null, 0, 10, OWNER_ID);
        assertEquals(0, empty.size());
    }

    @Test
    void should_filterByType_when_findByCondition_given_type() {
        repository.save(createJdbcConnection(null));
        repository.save(createApiConnection(null));

        List<DatasourceConnection> jdbcResults = repository.findByCondition(null, DatasourceType.JDBC, null, 0, 10, OWNER_ID);
        assertEquals(1, jdbcResults.size());
        assertEquals(DatasourceType.JDBC, jdbcResults.getFirst().type());

        List<DatasourceConnection> apiResults = repository.findByCondition(null, DatasourceType.API, null, 0, 10, OWNER_ID);
        assertEquals(1, apiResults.size());
        assertEquals(DatasourceType.API, apiResults.getFirst().type());
    }

    @Test
    void should_filterByStatus_when_findByCondition_given_status() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));
        repository.updateStatus(saved.id(), DatasourceStatus.DISABLED);

        List<DatasourceConnection> enabled = repository.findByCondition(null, null, DatasourceStatus.ENABLED, 0, 10, OWNER_ID);
        assertEquals(0, enabled.size());

        List<DatasourceConnection> disabled = repository.findByCondition(null, null, DatasourceStatus.DISABLED, 0, 10, OWNER_ID);
        assertEquals(1, disabled.size());
    }

    @Test
    void should_countByCondition_when_countByCondition_given_filters() {
        repository.save(createJdbcConnection(null));
        repository.save(createApiConnection(null));

        long total = repository.countByCondition(null, null, null, OWNER_ID);
        assertEquals(2, total);

        long jdbcCount = repository.countByCondition(null, DatasourceType.JDBC, null, OWNER_ID);
        assertEquals(1, jdbcCount);
    }

    @Test
    void should_paginate_when_findByCondition_given_pageAndSize() {
        for (int i = 0; i < 5; i++) {
            DatasourceConnection conn = new DatasourceConnection(
                    null, "ds-" + i, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                    new JdbcConnectionConfig("host", 3306, "db", "user", "pass", null),
                    null, OWNER_ID, null, null, null, null
            );
            repository.save(conn);
        }

        List<DatasourceConnection> page1 = repository.findByCondition(null, null, null, 1, 2, OWNER_ID);
        assertEquals(2, page1.size());

        List<DatasourceConnection> page3 = repository.findByCondition(null, null, null, 3, 2, OWNER_ID);
        assertEquals(1, page3.size());
    }

    private DatasourceConnection createJdbcConnection(Long id) {
        return new DatasourceConnection(id, "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass", null),
                null, OWNER_ID, null, null, null, null);
    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-ds", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, OWNER_ID, null, null, null, null);
    }

    // ==================== 归属隔离（owner 维度查询） ====================

    /** 他人归属用户 ID（横向越权矩阵用）。 */
    private static final Long OTHER_OWNER_ID = 2L;

    /** 归属一致时按 (id, owner) 可命中。 */
    @Test
    void should_findByIdAndOwner_when_saved_given_ownerMatches() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        Optional<DatasourceConnection> found = repository.findByIdAndOwner(saved.id(), OWNER_ID);

        assertTrue(found.isPresent());
        assertEquals(saved.name(), found.get().name());
    }

    /** 归属不匹配时按 (id, owner) 无命中——与「不存在」同形（不泄露存在性）。 */
    @Test
    void should_returnEmpty_when_findByIdAndOwner_given_otherOwner() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        Optional<DatasourceConnection> found = repository.findByIdAndOwner(saved.id(), OTHER_OWNER_ID);

        assertTrue(found.isEmpty());
    }

    /** existsOwned：归属一致为 true、他人为 false。 */
    @Test
    void should_reportOwnership_when_existsOwned_given_ownerAndOtherOwner() {
        DatasourceConnection saved = repository.save(createJdbcConnection(null));

        assertTrue(repository.existsOwned(saved.id(), OWNER_ID));
        assertFalse(repository.existsOwned(saved.id(), OTHER_OWNER_ID));
    }

    /** 按 (name, owner) 命中：owner 内唯一口径下的判重查询入口。 */
    @Test
    void should_findByNameAndOwner_when_saved_given_ownerMatches() {
        repository.save(createJdbcConnection(null));

        Optional<DatasourceConnection> found = repository.findByNameAndOwner("test-ds", OWNER_ID);

        assertTrue(found.isPresent());
        assertEquals("test-ds", found.get().name());
        assertTrue(repository.findByNameAndOwner("test-ds", OTHER_OWNER_ID).isEmpty());
    }

    /** 列表 / 计数按 owner 收敛：他人 owner 查不到本用户数据源。 */
    @Test
    void should_scopeToOwner_when_findByConditionAndCount_given_otherOwner() {
        repository.save(createJdbcConnection(null));

        assertEquals(1, repository.findByCondition(null, null, null, 1, 20, OWNER_ID).size());
        assertEquals(0, repository.findByCondition(null, null, null, 1, 20, OTHER_OWNER_ID).size());
        assertEquals(1L, repository.countByCondition(null, null, null, OWNER_ID));
        assertEquals(0L, repository.countByCondition(null, null, null, OTHER_OWNER_ID));
    }
}
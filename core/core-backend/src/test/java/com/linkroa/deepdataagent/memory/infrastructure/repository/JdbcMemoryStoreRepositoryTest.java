package com.linkroa.deepdataagent.memory.infrastructure.repository;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcMemoryStoreRepository} 记忆库仓储单测。
 * <p>覆盖保存回读、分页偏移换算、状态枚举转换与归档 / 统计 / 物理删除委托。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcMemoryStoreRepositoryTest {

    @Mock private MemoryStoreMapper mapper;

    private JdbcMemoryStoreRepository repository;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 4, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        repository = new JdbcMemoryStoreRepository(mapper);
    }

    private MemoryStoreEntity storeEntity(String storeId, String status) {
        MemoryStoreEntity entity = new MemoryStoreEntity();
        entity.setId(5L);
        entity.setStoreId(storeId);
        entity.setName("主记忆库");
        entity.setDescription("用于沉淀分析结论");
        entity.setStatus(status);
        entity.setEntryCount(3);
        entity.setTotalSize(2048L);
        entity.setOwnerId(1L);
        entity.setArchivedAt(MemoryStoreStatus.ARCHIVED.getValue().equals(status) ? NOW : null);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    @Test
    void should_resetIdAndReloadDomain_when_save_given_newStore() {
        // given（入库前清空主键，插入后回读带出数据库细节字段）
        MemoryStore store = MemoryStore.create("ms_1", "主记忆库", "用于沉淀分析结论", 1L);
        when(mapper.selectByStoreId("ms_1")).thenReturn(storeEntity("ms_1", "active"));

        // when
        MemoryStore saved = repository.save(store);

        // then（插入实体：status=active、统计初始值、无主键）
        ArgumentCaptor<MemoryStoreEntity> captor = ArgumentCaptor.forClass(MemoryStoreEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("ms_1", captor.getValue().getStoreId());
        assertEquals("active", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getEntryCount());
        assertEquals(0L, captor.getValue().getTotalSize());
        assertEquals(5L, saved.id());
        assertEquals(MemoryStoreStatus.ACTIVE, saved.status());
    }

    @Test
    void should_returnGivenStore_when_save_given_reloadMissing() {
        // given（回读缺失时兜底返回入参领域对象）
        MemoryStore store = MemoryStore.create("ms_1", "主记忆库", null, 1L);

        // when
        MemoryStore saved = repository.save(store);

        // then
        assertEquals(store, saved);
        verify(mapper).insert(any(MemoryStoreEntity.class));
    }

    @Test
    void should_returnEmpty_when_findByStoreId_given_missingRow() {
        // given
        when(mapper.selectByStoreId("ms_gone")).thenReturn(null);

        // when / then
        assertTrue(repository.findByStoreId("ms_gone").isEmpty());
    }

    @Test
    void should_convertArchivedStatus_when_findByStoreIdForUpdate_given_archivedRow() {
        // given（归档行的 status / archived_at 映射为领域枚举与时间）
        when(mapper.selectByStoreIdForUpdate("ms_1")).thenReturn(storeEntity("ms_1", "archived"));

        // when
        assertTrue(repository.findByStoreIdForUpdate("ms_1").isPresent());

        // then
        MemoryStore found = repository.findByStoreIdForUpdate("ms_1").orElseThrow();
        assertEquals(MemoryStoreStatus.ARCHIVED, found.status());
        assertTrue(found.archived());
        assertEquals(NOW, found.archivedAt());
        assertEquals(3, found.entryCount());
        assertEquals(2048L, found.totalSize());
    }

    @Test
    void should_mapDomainList_when_findByIds_given_ownedRows() {
        // given
        when(mapper.selectByIds(1L, List.of("ms_1", "ms_2")))
                .thenReturn(List.of(storeEntity("ms_1", "active"), storeEntity("ms_2", "active")));

        // when
        List<MemoryStore> stores = repository.findByIds(1L, List.of("ms_1", "ms_2"));

        // then
        assertEquals(2, stores.size());
        assertEquals("ms_1", stores.get(0).storeId());
        assertEquals("ms_2", stores.get(1).storeId());
    }

    @Test
    void should_returnEmpty_when_findByIds_given_mapperShortCircuit() {
        // given（空入参由 mapper default 方法短路为空列表）
        when(mapper.selectByIds(anyLong(), anyList())).thenReturn(List.of());

        // when / then
        assertTrue(repository.findByIds(1L, List.of()).isEmpty());
    }

    @Test
    void should_computeOffsetForSecondPage_when_findByPage_given_pageTwoSizeTen() {
        // given（第二页：offset = (2-1) * 10 = 10）
        when(mapper.selectPage(1L, 10L, 10)).thenReturn(List.of(storeEntity("ms_1", "active")));

        // when
        List<MemoryStore> stores = repository.findByPage(1L, 2, 10);

        // then
        assertEquals(1, stores.size());
        verify(mapper).selectPage(1L, 10L, 10);
    }

    @Test
    void should_clampOffsetToZero_when_findByPage_given_pageBelowOne() {
        // given（页码非法时偏移钳制为 0）
        when(mapper.selectPage(1L, 0L, 20)).thenReturn(List.of());

        // when
        repository.findByPage(1L, 0, 20);

        // then
        verify(mapper).selectPage(1L, 0L, 20);
    }

    @Test
    void should_delegateCount_when_countByOwnerId_given_ownerId() {
        // given
        when(mapper.countByOwnerId(1L)).thenReturn(7L);

        // when / then
        assertEquals(7L, repository.countByOwnerId(1L));
    }

    @Test
    void should_delegateConditionalArchive_when_archive_given_activeStore() {
        // given（条件归档影响行数原样透出，供上层判定幂等）
        when(mapper.archive("ms_1", NOW)).thenReturn(1);

        // when / then
        assertEquals(1, repository.archive("ms_1", NOW));
        verify(mapper).archive("ms_1", NOW);
    }

    @Test
    void should_delegateStatsAdjustment_when_adjustStats_given_negativeDeltas() {
        // given（删除条目时的负增量原样透传）
        when(mapper.adjustStats("ms_1", -1, -2048L)).thenReturn(1);

        // when / then
        assertEquals(1, repository.adjustStats("ms_1", -1, -2048L));
    }

    @Test
    void should_delegatePhysicalDelete_when_deleteByStoreId_given_storeId() {
        // when
        repository.deleteByStoreId("ms_1");

        // then
        verify(mapper).deleteByStoreId("ms_1");
        verify(mapper, never()).selectByStoreId(any());
    }
}

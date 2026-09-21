package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcKnowledgeBaseRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcKnowledgeBaseRepositoryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @Mock
    private KnowledgeBaseMapper mapper;

    @InjectMocks
    private JdbcKnowledgeBaseRepository repository;

    private static KnowledgeBaseEntity entityOf(Long id, String name, String status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription("描述");
        entity.setLifecycleStatus(status);
        entity.setRagEngineConfig("{\"engineType\":\"DOCUMENT_ENGINE\"}");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    private static KnowledgeBase aggregateOf(Long id, String name, LifecycleStatus status) {
        return KnowledgeBase.restore(id, name, "描述", "Chinese", status,
                null, "{\"engineType\":\"DOCUMENT_ENGINE\"}", null, null, null, null, null, NOW, NOW);
    }

    @Test
    void should_returnPersistedAggregate_when_save_given_newAggregateWithoutId() {
        // given
        KnowledgeBase kb = KnowledgeBase.create("新库", "描述", null, null, null, null, null, null, null);
        AtomicReference<Long> idAtInsert = new AtomicReference<>(1L);
        doAnswer(invocation -> {
            KnowledgeBaseEntity argument = invocation.getArgument(0);
            idAtInsert.set(argument.getId());
            argument.setId(100L);
            return 1;
        }).when(mapper).insert(any(KnowledgeBaseEntity.class));
        when(mapper.selectById(100L)).thenReturn(entityOf(100L, "新库", "ACTIVE"));

        // when
        KnowledgeBase saved = repository.save(kb);

        // then
        assertNull(idAtInsert.get());
        assertEquals(100L, saved.id());
        verify(mapper).insert(any(KnowledgeBaseEntity.class));
        verify(mapper, never()).updateById(any(KnowledgeBaseEntity.class));
    }

    @Test
    void should_returnOriginalAggregate_when_save_given_reselectMissesRecord() {
        // given
        KnowledgeBase kb = KnowledgeBase.create("回退库", null, null, null, null, null, null, null, null);
        doAnswer(invocation -> {
            ((KnowledgeBaseEntity) invocation.getArgument(0)).setId(101L);
            return 1;
        }).when(mapper).insert(any(KnowledgeBaseEntity.class));
        when(mapper.selectById(101L)).thenReturn(null);

        // when
        KnowledgeBase saved = repository.save(kb);

        // then
        assertEquals("回退库", saved.name());
    }

    @Test
    void should_callUpdateById_when_update_given_existingAggregate() {
        // given
        KnowledgeBase kb = aggregateOf(7L, "已存在库", LifecycleStatus.ACTIVE);
        when(mapper.selectById(7L)).thenReturn(entityOf(7L, "已存在库", "ACTIVE"));

        // when
        KnowledgeBase updated = repository.update(kb);

        // then
        ArgumentCaptor<KnowledgeBaseEntity> captor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals("已存在库", captor.getValue().getName());
        verify(mapper, never()).insert(any(KnowledgeBaseEntity.class));
        assertEquals(7L, updated.id());
    }

    @Test
    void should_returnEmpty_when_findById_given_noRecord() {
        // given
        when(mapper.selectById(404L)).thenReturn(null);

        // when
        Optional<KnowledgeBase> found = repository.findById(404L);

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void should_returnAggregate_when_findByName_given_existingRecord() {
        // given
        when(mapper.selectByName("唯一库")).thenReturn(entityOf(8L, "唯一库", "ACTIVE"));

        // when
        Optional<KnowledgeBase> found = repository.findByName("唯一库");

        // then
        assertTrue(found.isPresent());
        assertEquals(8L, found.get().id());
        assertEquals(LifecycleStatus.ACTIVE, found.get().lifecycleStatus());
    }

    @Test
    void should_returnAggregate_when_findByIdForUpdate_given_lockedRecord() {
        // given
        when(mapper.selectByIdForUpdate(9L)).thenReturn(entityOf(9L, "锁库", "DELETING"));

        // when
        Optional<KnowledgeBase> found = repository.findByIdForUpdate(9L);

        // then
        assertTrue(found.isPresent());
        assertEquals(LifecycleStatus.DELETING, found.get().lifecycleStatus());
        verify(mapper, never()).selectById(9L);
    }

    @Test
    void should_convertPageToOffset_when_findByCondition_given_statusAndPage() {
        // given
        when(mapper.selectByCondition("kw", "ACTIVE", KnowledgeBaseSortField.CREATED_AT, false, 20L, 10))
                .thenReturn(List.of(entityOf(1L, "库一", "ACTIVE"), entityOf(2L, "库二", "ACTIVE")));

        // when
        List<KnowledgeBase> found = repository.findByCondition("kw", LifecycleStatus.ACTIVE,
                KnowledgeBaseSortField.CREATED_AT, false, 3, 10);

        // then
        assertEquals(2, found.size());
        assertEquals("库一", found.get(0).name());
        assertEquals("库二", found.get(1).name());
        verify(mapper).selectByCondition("kw", "ACTIVE", KnowledgeBaseSortField.CREATED_AT, false, 20L, 10);
    }

    @Test
    void should_passNullStatus_when_findByCondition_given_nullStatusAndFirstPage() {
        // given
        when(mapper.selectByCondition(null, null, KnowledgeBaseSortField.UPDATED_AT, true, 0L, 20))
                .thenReturn(List.of());

        // when
        List<KnowledgeBase> found = repository.findByCondition(null, null,
                KnowledgeBaseSortField.UPDATED_AT, true, 1, 20);

        // then
        assertTrue(found.isEmpty());
        verify(mapper).selectByCondition(null, null, KnowledgeBaseSortField.UPDATED_AT, true, 0L, 20);
    }

    @Test
    void should_delegateCount_when_countByCondition_given_keywordAndStatus() {
        // given
        when(mapper.countByCondition("kw", "DELETE_FAILED")).thenReturn(5L);

        // when
        long total = repository.countByCondition("kw", LifecycleStatus.DELETE_FAILED);

        // then
        assertEquals(5L, total);
    }

    @Test
    void should_passNullStatusName_when_countByStatus_given_nullStatus() {
        // given
        when(mapper.countByStatus(null)).thenReturn(12L);

        // when
        long total = repository.countByStatus(null);

        // then
        assertEquals(12L, total);
        verify(mapper).countByStatus(null);
    }

    @Test
    void should_delegateLogicDelete_when_deleteById_given_id() {
        // when
        repository.deleteById(3L);

        // then
        verify(mapper).deleteById(3L);
    }

    @Test
    void should_delegateToMapper_when_findIdsByLifecycle_given_deletingStatus() {
        // given：启动恢复扫描按枚举名下传查询
        when(mapper.selectAllIdsByLifecycleStatus("DELETING")).thenReturn(List.of(3L, 8L));

        // when
        List<Long> ids = repository.findIdsByLifecycle(LifecycleStatus.DELETING);

        // then
        assertEquals(List.of(3L, 8L), ids);
        verify(mapper).selectAllIdsByLifecycleStatus("DELETING");
    }

    @Test
    void should_returnEmptyWithoutQuery_when_findIdsByLifecycle_given_nullStatus() {
        // when // then：空入参零 DB 交互
        assertTrue(repository.findIdsByLifecycle(null).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void should_returnTrue_when_transitLifecycle_given_casHit() {
        // given：单条条件 UPDATE 命中 1 行（ACTIVE → DELETING）
        when(mapper.transitLifecycleStatus(7L, "ACTIVE", "DELETING")).thenReturn(1);

        // when
        boolean hit = repository.transitLifecycle(7L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING);

        // then：命中返回 true，且以枚举名精确下传 CAS 条件
        assertTrue(hit);
        verify(mapper).transitLifecycleStatus(7L, "ACTIVE", "DELETING");
    }

    @Test
    void should_returnFalse_when_transitLifecycle_given_casMiss() {
        // given：状态不符 / 记录不存在 / 逻辑删除行不可达，SQL 均表现为零行受影响
        when(mapper.transitLifecycleStatus(7L, "DELETING", "DELETE_FAILED")).thenReturn(0);

        // when
        boolean hit = repository.transitLifecycle(7L, LifecycleStatus.DELETING, LifecycleStatus.DELETE_FAILED);

        // then：未命中幂等空转，仅一次条件更新、无任何先查后写
        assertFalse(hit);
        verify(mapper).transitLifecycleStatus(7L, "DELETING", "DELETE_FAILED");
        verify(mapper, never()).selectById(any());
    }

    @Test
    void should_returnFalseWithoutAnyStatement_when_transitLifecycle_given_illegalArguments() {
        // when：kbId 为空 / 起点状态为空 / 目标状态为空
        boolean byNullId = repository.transitLifecycle(null, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING);
        boolean byNullFrom = repository.transitLifecycle(7L, null, LifecycleStatus.DELETING);
        boolean byNullTo = repository.transitLifecycle(7L, LifecycleStatus.ACTIVE, null);

        // then：入参非法视为未命中且零 DB 交互
        assertFalse(byNullId);
        assertFalse(byNullFrom);
        assertFalse(byNullTo);
        verifyNoInteractions(mapper);
    }

    @Test
    void should_returnTrue_when_executeDelete_given_conditionDeleteHit() {
        // given：收口条件物理 DELETE（delete(wrapper) 命中可收口态行）返回 1 行受影响
        when(mapper.delete(any())).thenReturn(1);

        // when
        boolean deleted = repository.executeDelete(7L);

        // then：命中返回 true，仅一次条件物理删、不触碰 deleteById
        assertTrue(deleted);
        verify(mapper).delete(any());
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void should_returnFalse_when_executeDelete_given_zeroRowMiss() {
        // given：0 行命中＝行已不存在（已收口）或状态不符，幂等空转
        when(mapper.delete(any())).thenReturn(0);

        // when
        boolean deleted = repository.executeDelete(7L);

        // then
        assertFalse(deleted);
        verify(mapper).delete(any());
    }

    @Test
    void should_returnFalseWithoutAnyStatement_when_executeDelete_given_nullId() {
        // when // then：入参非法视为已收口幂等，零 DB 交互
        assertFalse(repository.executeDelete(null));
        verifyNoInteractions(mapper);
    }

    @Test
    void should_returnTrue_when_markFailed_given_casHit() {
        // given：DELETING → DELETE_FAILED 单语句 CAS 命中并同写留痕
        when(mapper.transitLifecycleStatusWithMessage(7L, "DELETING", "DELETE_FAILED",
                "[KB-CLEANUP] step=chunk_cleanup")).thenReturn(1);

        // when
        boolean hit = repository.markFailed(7L, "[KB-CLEANUP] step=chunk_cleanup");

        // then：命中返回 true，源态与目标态以枚举名下传
        assertTrue(hit);
        verify(mapper).transitLifecycleStatusWithMessage(7L, "DELETING", "DELETE_FAILED",
                "[KB-CLEANUP] step=chunk_cleanup");
        verify(mapper, never()).selectById(any());
    }

    @Test
    void should_returnFalse_when_markFailed_given_casMiss() {
        // given：非 DELETING 源态（已收口行缺失 / 已 DELETE_FAILED / ACTIVE）零行命中
        when(mapper.transitLifecycleStatusWithMessage(7L, "DELETING", "DELETE_FAILED", null)).thenReturn(0);

        // when
        boolean hit = repository.markFailed(7L, null);

        // then：未命中幂等空转，不覆盖并发链已写入的状态
        assertFalse(hit);
        verify(mapper).transitLifecycleStatusWithMessage(7L, "DELETING", "DELETE_FAILED", null);
    }

    @Test
    void should_returnFalseWithoutAnyStatement_when_markFailed_given_nullId() {
        // when // then：入参非法视为未命中且零 DB 交互
        assertFalse(repository.markFailed(null, "[KB-CLEANUP] step=x"));
        verifyNoInteractions(mapper);
    }
}

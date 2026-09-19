package com.linkroa.deepdataagent.memory.infrastructure.repository;

import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryVersionEntity;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryMapper;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper.MemoryVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcMemoryRepository} 条目与版本仓储单测。
 * <p>覆盖双写、OCC 条件写、tombstone、redact、级联删除顺序与实体 ⇄ 领域转换。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcMemoryRepositoryTest {

    @Mock private MemoryMapper memoryMapper;
    @Mock private MemoryVersionMapper versionMapper;

    private JdbcMemoryRepository repository;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 4, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        repository = new JdbcMemoryRepository(memoryMapper, versionMapper);
    }

    private MemoryEntity memoryEntity(int version) {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(11L);
        entity.setMemoryId("mem_1");
        entity.setStoreId("ms_1");
        entity.setPath("docs/README.md");
        entity.setVersion(version);
        entity.setSize(12L);
        entity.setContentSha256("0".repeat(64));
        entity.setMetadata("{\"k\":\"v\"}");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    private MemoryVersionEntity versionEntity(int version) {
        MemoryVersionEntity entity = new MemoryVersionEntity();
        entity.setId(21L);
        entity.setVersionId("memver_1");
        entity.setStoreId("ms_1");
        entity.setEntryId("mem_1");
        entity.setEntryPath("docs/README.md");
        entity.setVersion(version);
        entity.setAction("created");
        entity.setContent("初始内容");
        entity.setSize(12L);
        entity.setContentSha256("0".repeat(64));
        entity.setRedacted(false);
        entity.setCreatedAt(NOW);
        return entity;
    }

    @Test
    void should_createEntryAndReloadDomain_when_createEntry_given_newMemory() {
        // given（新建条目：双写条目行与 created 版本行，随后回读带出数据库主键）
        Memory memory = Memory.create("mem_1", "ms_1", "docs/README.md", "初始内容", null);
        MemoryVersion createdVersion =
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "docs/README.md", 1, "初始内容");
        when(memoryMapper.selectByMemoryId("mem_1")).thenReturn(memoryEntity(1));

        // when
        Memory saved = repository.createEntry(memory, createdVersion);

        // then（条目行字段与 metadata 默认 JSON、版本行 action=created）
        ArgumentCaptor<MemoryEntity> memoryCaptor = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryMapper).insertEntry(memoryCaptor.capture());
        assertEquals("mem_1", memoryCaptor.getValue().getMemoryId());
        assertEquals("ms_1", memoryCaptor.getValue().getStoreId());
        assertEquals(1, memoryCaptor.getValue().getVersion());
        assertEquals("{}", memoryCaptor.getValue().getMetadata());
        ArgumentCaptor<MemoryVersionEntity> versionCaptor = ArgumentCaptor.forClass(MemoryVersionEntity.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertEquals("memver_1", versionCaptor.getValue().getVersionId());
        assertEquals("created", versionCaptor.getValue().getAction());
        assertEquals("初始内容", versionCaptor.getValue().getContent());
        assertEquals(11L, saved.id());
    }

    @Test
    void should_returnGivenMemory_when_createEntry_given_reloadMissing() {
        // given（回读缺失时兜底返回入参领域对象）
        Memory memory = Memory.create("mem_1", "ms_1", "docs/README.md", "初始内容", null);
        MemoryVersion createdVersion =
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "docs/README.md", 1, "初始内容");

        // when
        Memory saved = repository.createEntry(memory, createdVersion);

        // then
        assertEquals(memory, saved);
        verify(memoryMapper).insertEntry(any(MemoryEntity.class));
        verify(versionMapper).insert(any(MemoryVersionEntity.class));
    }

    @Test
    void should_convertMetadataJson_when_findByMemoryId_given_existingRow() {
        // given（metadata JSONB 文本 → 值对象）
        when(memoryMapper.selectByMemoryId("mem_1")).thenReturn(memoryEntity(2));

        // when
        Optional<Memory> found = repository.findByMemoryId("mem_1");

        // then
        assertTrue(found.isPresent());
        assertEquals(2, found.get().version());
        assertEquals("v", found.get().metadata().entries().get("k"));
    }

    @Test
    void should_returnEmpty_when_findByMemoryId_given_missingRow() {
        // given
        when(memoryMapper.selectByMemoryId("mem_missing")).thenReturn(null);

        // when / then
        assertTrue(repository.findByMemoryId("mem_missing").isEmpty());
    }

    @Test
    void should_delegateLockQuery_when_findByMemoryIdForUpdate_given_existingRow() {
        // given（锁行查询走 selectByMemoryIdForUpdate）
        when(memoryMapper.selectByMemoryIdForUpdate("mem_1")).thenReturn(memoryEntity(1));

        // when / then
        assertTrue(repository.findByMemoryIdForUpdate("mem_1").isPresent());
        verify(memoryMapper).selectByMemoryIdForUpdate("mem_1");
    }

    @Test
    void should_mapAllActiveEntries_when_listByStoreId_given_twoRows() {
        // given
        MemoryEntity first = memoryEntity(1);
        MemoryEntity second = memoryEntity(2);
        second.setMemoryId("mem_2");
        second.setPath("docs/GUIDE.md");
        when(memoryMapper.selectActiveByStoreId("ms_1")).thenReturn(List.of(first, second));

        // when
        List<Memory> entries = repository.listByStoreId("ms_1");

        // then（保持 mapper 返回顺序）
        assertEquals(2, entries.size());
        assertEquals("docs/README.md", entries.get(0).path());
        assertEquals("mem_2", entries.get(1).memoryId());
    }

    @Test
    void should_returnFalseWithoutVersion_when_updateEntry_given_casConflict() {
        // given（CAS 影响行数 0：版本已被并发推进）
        Memory updated = Memory.create("mem_1", "ms_1", "docs/README.md", "初始内容", null)
                .nextVersion("新内容", null);
        MemoryVersion updatedVersion =
                MemoryVersion.updated("memver_2", "ms_1", "mem_1", "docs/README.md", 2, "新内容");
        when(memoryMapper.updateContentCas(any(MemoryEntity.class), anyInt())).thenReturn(0);

        // when
        boolean result = repository.updateEntry(updated, 1, updatedVersion);

        // then（更新失败且不追加版本行）
        assertFalse(result);
        verify(versionMapper, never()).insert(any(MemoryVersionEntity.class));
    }

    @Test
    void should_appendUpdatedVersion_when_updateEntry_given_casSuccess() {
        // given（CAS 成功：追加 action=updated 的版本行）
        Memory updated = Memory.create("mem_1", "ms_1", "docs/README.md", "初始内容", null)
                .nextVersion("新内容", null);
        MemoryVersion updatedVersion =
                MemoryVersion.updated("memver_2", "ms_1", "mem_1", "docs/README.md", 2, "新内容");
        when(memoryMapper.updateContentCas(any(MemoryEntity.class), anyInt())).thenReturn(1);

        // when
        boolean result = repository.updateEntry(updated, 1, updatedVersion);

        // then
        assertTrue(result);
        ArgumentCaptor<MemoryVersionEntity> captor = ArgumentCaptor.forClass(MemoryVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals("updated", captor.getValue().getAction());
        assertEquals(2, captor.getValue().getVersion());
        assertEquals("新内容", captor.getValue().getContent());
    }

    @Test
    void should_tombstoneAndAppendDeletedVersion_when_deleteEntry_given_activeRow() {
        // given（软删：置 deleted_at 并追加 tombstone 版本）
        MemoryVersion tombstone =
                MemoryVersion.tombstone("memver_3", "ms_1", "mem_1", "docs/README.md", 3);
        when(memoryMapper.tombstone("mem_1", tombstone.createdAt())).thenReturn(1);

        // when
        boolean result = repository.deleteEntry("mem_1", tombstone);

        // then（墓碑版本不携带内容）
        assertTrue(result);
        verify(memoryMapper).tombstone("mem_1", tombstone.createdAt());
        ArgumentCaptor<MemoryVersionEntity> captor = ArgumentCaptor.forClass(MemoryVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals("deleted", captor.getValue().getAction());
        assertNull(captor.getValue().getContent());
    }

    @Test
    void should_returnFalse_when_deleteEntry_given_tombstoneMiss() {
        // given（条目已删除：软删影响行数 0）
        MemoryVersion tombstone =
                MemoryVersion.tombstone("memver_3", "ms_1", "mem_1", "docs/README.md", 3);
        when(memoryMapper.tombstone(anyString(), any(OffsetDateTime.class))).thenReturn(0);

        // when / then
        assertFalse(repository.deleteEntry("mem_1", tombstone));
        verify(versionMapper, never()).insert(any(MemoryVersionEntity.class));
    }

    @Test
    void should_convertActionEnum_when_findVersion_given_existingRow() {
        // given
        when(versionMapper.selectByVersionId("memver_1")).thenReturn(versionEntity(1));

        // when
        Optional<MemoryVersion> found = repository.findVersion("memver_1");

        // then
        assertTrue(found.isPresent());
        assertEquals(MemoryVersionAction.CREATED, found.get().action());
        assertFalse(found.get().redacted());
    }

    @Test
    void should_delegateLockQueryAndReturnEmpty_when_findVersionForUpdate_given_missingRow() {
        // given
        when(versionMapper.selectByVersionIdForUpdate("memver_x")).thenReturn(null);

        // when / then
        assertTrue(repository.findVersionForUpdate("memver_x").isEmpty());
        verify(versionMapper).selectByVersionIdForUpdate("memver_x");
    }

    @Test
    void should_mapVersions_when_listVersions_given_multipleRows() {
        // given
        when(versionMapper.selectByEntryIdOrderByVersionDesc("mem_1"))
                .thenReturn(List.of(versionEntity(2), versionEntity(1)));

        // when
        List<MemoryVersion> versions = repository.listVersions("mem_1");

        // then
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).version());
        assertEquals(1, versions.get(1).version());
    }

    @Test
    void should_returnVersionSnapshot_when_findVersionAt_given_hitRow() {
        // given
        when(versionMapper.selectByEntryIdAndVersion("mem_1", 2)).thenReturn(versionEntity(2));

        // when
        Optional<MemoryVersion> found = repository.findVersionAt("mem_1", 2);

        // then
        assertTrue(found.isPresent());
        assertEquals(2, found.get().version());
    }

    @Test
    void should_returnTrue_when_redactVersion_given_rowUpdated() {
        // given
        when(versionMapper.redactByVersionId("memver_1", NOW)).thenReturn(1);

        // when / then
        assertTrue(repository.redactVersion("memver_1", NOW));
    }

    @Test
    void should_returnFalse_when_redactVersion_given_alreadyRedactedOrMissing() {
        // given（已脱敏或不存在：影响行数 0）
        when(versionMapper.redactByVersionId(anyString(), any(OffsetDateTime.class))).thenReturn(0);

        // when / then
        assertFalse(repository.redactVersion("memver_1", NOW));
    }

    @Test
    void should_mapRedactedSnapshotWithPreservedSize_when_findVersion_given_redactedRow() {
        // given（版本级 redact 清除后的行：内容与校验值已为 null，脱敏标记与时间落列）
        MemoryVersionEntity entity = versionEntity(2);
        entity.setVersionId("memver_2");
        entity.setAction("updated");
        entity.setRedacted(true);
        entity.setContent(null);
        entity.setContentSha256(null);
        entity.setRedactedAt(NOW);
        when(versionMapper.selectByVersionId("memver_2")).thenReturn(entity);

        // when
        Optional<MemoryVersion> found = repository.findVersion("memver_2");

        // then（脱敏领域映射：content / sha 为 null，size 保留，标记与时间透出）
        assertTrue(found.isPresent());
        assertTrue(found.get().redacted());
        assertNull(found.get().content());
        assertNull(found.get().contentSha256());
        assertEquals(12L, found.get().size());
        assertEquals(NOW, found.get().redactedAt());
    }

    @Test
    void should_deleteVersionsBeforeEntries_when_deleteByStoreId_given_storeId() {
        // given（级联物理删除：先版本行后条目行，避免外键/悬挂引用）

        // when
        repository.deleteByStoreId("ms_1");

        // then
        InOrder inOrder = inOrder(versionMapper, memoryMapper);
        inOrder.verify(versionMapper).deleteByStoreId("ms_1");
        inOrder.verify(memoryMapper).deleteByStoreId("ms_1");
    }
}

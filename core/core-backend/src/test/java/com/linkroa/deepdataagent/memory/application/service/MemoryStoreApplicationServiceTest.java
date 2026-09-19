package com.linkroa.deepdataagent.memory.application.service;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.command.RedactMemoryVersionCommand;
import com.linkroa.deepdataagent.memory.application.command.UpdateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.query.ListMemoryStoreQuery;
import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryDetail;
import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryRepository;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryStoreApplicationService} 三阶用例编排单测。
 */
@ExtendWith(MockitoExtension.class)
class MemoryStoreApplicationServiceTest {

    @Mock private MemoryStoreRepository memoryStoreRepository;
    @Mock private MemoryRepository memoryRepository;
    @Mock private SessionReferenceApi sessionReferenceApi;
    @Mock private TransactionTemplate transactionTemplate;

    private MemoryStoreApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = new MemoryStoreApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "memoryStoreRepository", memoryStoreRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "memoryRepository", memoryRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionReferenceApi", sessionReferenceApi);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // 条目写入事务内 FOR UPDATE 锁行复检默认读到 active（归档竞态用例单独覆写为 archived）
        lenient().when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private MemoryStore buildStore(String storeId, MemoryStoreStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new MemoryStore(1L, storeId, "会话记忆", "", status, 0, 0L, 1L,
                status == MemoryStoreStatus.ARCHIVED ? now : null, now, now, null, null);
    }

    private Memory buildMemory(int version, long size) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Memory(10L, "mem_1", "ms_1", "notes/a", version, size, "a".repeat(64),
                MemoryMetadata.empty(), now, now);
    }

    // ===== Store 用例 =====

    @Test
    void should_generateMsPrefixedActiveStore_when_create_given_validCommand() {
        // given
        when(memoryStoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemoryStore saved = service.create(new CreateMemoryStoreCommand("长期记忆", "备注"));

        // then
        assertTrue(saved.storeId().startsWith("ms_"));
        assertEquals("长期记忆", saved.name());
        assertEquals(MemoryStoreStatus.ACTIVE, saved.status());
        assertEquals(0, saved.entryCount());
    }

    @Test
    void should_delegateOwnerScopedQuery_when_list_given_pagedQuery() {
        // given
        when(memoryStoreRepository.findByPage(1L, 1, 20)).thenReturn(List.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));

        // when
        List<MemoryStore> stores = service.list(new ListMemoryStoreQuery(1, 20));

        // then
        assertEquals(1, stores.size());
    }

    @Test
    void should_countActiveStores_when_count_given_authenticatedOwner() {
        // given
        when(memoryStoreRepository.countByOwnerId(1L)).thenReturn(3L);

        // when
        long total = service.count();

        // then
        assertEquals(3L, total);
    }

    @Test
    void should_throwNotFound_when_get_given_notExist() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_missing")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get("ms_missing"));
    }

    @Test
    void should_throwNotFound_when_get_given_otherOwner() {
        // given
        OffsetDateTime now = OffsetDateTime.now();
        MemoryStore other = new MemoryStore(1L, "ms_1", "别人", "", MemoryStoreStatus.ACTIVE,
                0, 0L, 99L, null, now, now, null, null);
        when(memoryStoreRepository.findByStoreId("ms_1")).thenReturn(Optional.of(other));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get("ms_1"));
    }

    @Test
    void should_returnStore_when_get_given_archivedStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when
        MemoryStore store = service.get("ms_1");

        // then
        assertTrue(store.archived());
    }

    @Test
    void should_archiveStore_when_archive_given_activeStore() {
        // given
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when
        MemoryStore result = service.archive("ms_1");

        // then
        verify(memoryStoreRepository).archive(eq("ms_1"), any(OffsetDateTime.class));
        assertTrue(result.archived());
    }

    @Test
    void should_returnCurrentState_when_archive_given_alreadyArchived() {
        // given
        MemoryStore archived = buildStore("ms_1", MemoryStoreStatus.ARCHIVED);
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1")).thenReturn(Optional.of(archived));

        // when
        MemoryStore result = service.archive("ms_1");

        // then
        assertEquals(archived, result);
        verify(memoryStoreRepository, never()).archive(anyString(), any());
    }

    @Test
    void should_deleteCascade_when_delete_given_unreferencedStore() {
        // given
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(sessionReferenceApi.countSessionsByMemoryStoreId("ms_1")).thenReturn(0L);

        // when
        service.delete("ms_1");

        // then
        verify(memoryRepository).deleteByStoreId("ms_1");
        verify(memoryStoreRepository).deleteByStoreId("ms_1");
    }

    @Test
    void should_throwConflict_when_delete_given_referencedBySessions() {
        // given
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));
        when(sessionReferenceApi.countSessionsByMemoryStoreId("ms_1")).thenReturn(2L);

        // when // then（级联删条目与删库均不得发生）
        assertThrows(ResourceConflictException.class, () -> service.delete("ms_1"));
        verify(memoryRepository, never()).deleteByStoreId(anyString());
        verify(memoryStoreRepository, never()).deleteByStoreId(anyString());
    }

    // ===== Memory 条目用例 =====

    @Test
    void should_createEntryWithVersionAndStats_when_createEntry_given_validCommand() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.createEntry(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        long contentBytes = "内容".getBytes(StandardCharsets.UTF_8).length;

        // when
        MemoryDetail detail = service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", "内容", null));

        // then
        ArgumentCaptor<Memory> entryCaptor = ArgumentCaptor.forClass(Memory.class);
        ArgumentCaptor<MemoryVersion> versionCaptor = ArgumentCaptor.forClass(MemoryVersion.class);
        verify(memoryRepository).createEntry(entryCaptor.capture(), versionCaptor.capture());
        assertTrue(entryCaptor.getValue().memoryId().startsWith("mem_"));
        assertEquals(1, entryCaptor.getValue().version());
        assertEquals("created", versionCaptor.getValue().action().getValue());
        verify(memoryStoreRepository).adjustStats("ms_1", 1, contentBytes);
        assertEquals("内容", detail.content());
    }

    @Test
    void should_throwConflict_when_createEntry_given_archivedStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", "内容", null)));
        verify(memoryRepository, never()).createEntry(any(), any());
    }

    @Test
    void should_throwIllegalArgument_when_createEntry_given_contentExceeds100KB() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        String bigContent = "a".repeat(Memory.MAX_CONTENT_BYTES + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", bigContent, null)));
        verify(memoryRepository, never()).createEntry(any(), any());
    }

    @Test
    void should_updateEntryWithDeltaStats_when_updateEntry_given_matchingExpectedVersion() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(1, 6L)));
        when(memoryRepository.updateEntry(any(), eq(1), any())).thenReturn(true);
        long newBytes = "新内容".getBytes(StandardCharsets.UTF_8).length;

        // when
        MemoryDetail detail = service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1, null));

        // then
        ArgumentCaptor<Memory> updatedCaptor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryRepository).updateEntry(updatedCaptor.capture(), eq(1), any());
        assertEquals(2, updatedCaptor.getValue().version());
        verify(memoryStoreRepository).adjustStats("ms_1", 0, newBytes - 6L);
        assertEquals("新内容", detail.content());
        assertEquals(2, detail.entry().version());
    }

    @Test
    void should_throwConflict_when_updateEntry_given_staleExpectedVersion() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(3, 6L)));

        // when // then（409 消息携带两侧版本号，便于客户端对账重试）
        ResourceConflictException ex = assertThrows(ResourceConflictException.class, () -> service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 2, null)));
        assertTrue(ex.getMessage().contains("期望 2"));
        assertTrue(ex.getMessage().contains("当前 3"));
        verify(memoryRepository, never()).updateEntry(any(), anyInt(), any());
    }

    @Test
    void should_throwConflict_when_updateEntry_given_casUpdateFailed() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(1, 6L)));
        when(memoryRepository.updateEntry(any(), eq(1), any())).thenReturn(false);

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1, null)));
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_throwNotFound_when_updateEntry_given_entryOfAnotherStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        OffsetDateTime now = OffsetDateTime.now();
        Memory foreign = new Memory(10L, "mem_1", "ms_other", "notes/a", 1, 6L, "a".repeat(64),
                MemoryMetadata.empty(), now, now);
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(foreign));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1, null)));
    }

    @Test
    void should_writeTombstoneAndRegressStats_when_deleteEntry_given_existingEntry() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(3, 6L)));
        when(memoryRepository.deleteEntry(eq("mem_1"), any())).thenReturn(true);

        // when
        service.deleteEntry("ms_1", "mem_1");

        // then
        ArgumentCaptor<MemoryVersion> tombstoneCaptor = ArgumentCaptor.forClass(MemoryVersion.class);
        verify(memoryRepository).deleteEntry(eq("mem_1"), tombstoneCaptor.capture());
        assertEquals("deleted", tombstoneCaptor.getValue().action().getValue());
        assertEquals(4, tombstoneCaptor.getValue().version());
        verify(memoryStoreRepository).adjustStats("ms_1", -1, -6L);
    }

    @Test
    void should_throwNotFound_when_deleteEntry_given_missingEntry() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_missing")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteEntry("ms_1", "mem_missing"));
    }

    @Test
    void should_returnHeadContent_when_getEntry_given_existingEntry() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryId("mem_1")).thenReturn(Optional.of(buildMemory(2, 6L)));
        MemoryVersion head = MemoryVersion.updated("memver_2", "ms_1", "mem_1", "notes/a", 2, "最新");
        when(memoryRepository.findVersionAt("mem_1", 2)).thenReturn(Optional.of(head));

        // when
        MemoryDetail detail = service.getEntry("ms_1", "mem_1");

        // then
        assertEquals("最新", detail.content());
        assertEquals(2, detail.entry().version());
    }

    @Test
    void should_replaceMetadata_when_updateEntry_given_newMetadata() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(1, 6L)));
        when(memoryRepository.updateEntry(any(), eq(1), any())).thenReturn(true);

        // when
        service.updateEntry(new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1,
                MemoryMetadata.of(Map.of("k2", "v2"))));

        // then
        ArgumentCaptor<Memory> captor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryRepository).updateEntry(captor.capture(), eq(1), any());
        assertEquals("v2", captor.getValue().metadata().entries().get("k2"));
    }

    @Test
    void should_throwNotFound_when_getEntry_given_entryOfAnotherStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        OffsetDateTime now = OffsetDateTime.now();
        Memory foreign = new Memory(10L, "mem_1", "ms_other", "notes/a", 1, 6L, "a".repeat(64),
                MemoryMetadata.empty(), now, now);
        when(memoryRepository.findByMemoryId("mem_1")).thenReturn(Optional.of(foreign));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getEntry("ms_1", "mem_1"));
    }

    @Test
    void should_returnNullContent_when_getEntry_given_headVersionRedacted() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryId("mem_1")).thenReturn(Optional.of(buildMemory(2, 6L)));
        MemoryVersion redactedHead = new MemoryVersion(2L, "memver_2", "ms_1", "mem_1", "notes/a", 2,
                MemoryVersionAction.UPDATED, null, 6L, null, true, OffsetDateTime.now(), OffsetDateTime.now());
        when(memoryRepository.findVersionAt("mem_1", 2)).thenReturn(Optional.of(redactedHead));

        // when
        MemoryDetail detail = service.getEntry("ms_1", "mem_1");

        // then
        assertNull(detail.content());
    }

    @Test
    void should_listEntries_when_listEntries_given_ownedStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.listByStoreId("ms_1")).thenReturn(List.of(buildMemory(1, 6L)));

        // when
        List<Memory> entries = service.listEntries("ms_1");

        // then
        assertEquals(1, entries.size());
        assertEquals("notes/a", entries.get(0).path());
    }

    @Test
    void should_listVersions_when_listVersions_given_ownedEntry() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryId("mem_1")).thenReturn(Optional.of(buildMemory(2, 6L)));
        when(memoryRepository.listVersions("mem_1")).thenReturn(List.of(
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "旧")));

        // when
        List<MemoryVersion> versions = service.listVersions("ms_1", "mem_1");

        // then
        assertEquals(1, versions.size());
        assertEquals("created", versions.get(0).action().getValue());
    }

    @Test
    void should_returnVersion_when_getVersion_given_versionOfSameStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "旧");
        when(memoryRepository.findVersion("memver_1")).thenReturn(Optional.of(version));

        // when
        MemoryVersion result = service.getVersion("ms_1", "memver_1");

        // then
        assertFalse(result.redacted());
        assertEquals("旧", result.content());
    }

    @Test
    void should_throwNotFound_when_getVersion_given_versionOfAnotherStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        MemoryVersion foreign = MemoryVersion.created("memver_1", "ms_other", "mem_1", "notes/a", 1, "旧");
        when(memoryRepository.findVersion("memver_1")).thenReturn(Optional.of(foreign));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getVersion("ms_1", "memver_1"));
    }

    // ===== redact 用例 =====

    @Test
    void should_redactVersion_when_redactVersion_given_activeStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "敏感内容");
        when(memoryRepository.findVersionForUpdate("memver_1")).thenReturn(Optional.of(version));

        // when
        MemoryVersion result = service.redactVersion(new RedactMemoryVersionCommand("ms_1", "memver_1"));

        // then
        verify(memoryRepository).redactVersion(eq("memver_1"), any(OffsetDateTime.class));
        assertTrue(result.redacted());
        assertNull(result.content());
    }

    @Test
    void should_allowRedact_when_redactVersion_given_archivedStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "敏感内容");
        when(memoryRepository.findVersionForUpdate("memver_1")).thenReturn(Optional.of(version));

        // when
        MemoryVersion result = service.redactVersion(new RedactMemoryVersionCommand("ms_1", "memver_1"));

        // then
        assertTrue(result.redacted());
    }

    @Test
    void should_beIdempotent_when_redactVersion_given_alreadyRedacted() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        MemoryVersion redacted = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "x").redact();
        when(memoryRepository.findVersionForUpdate("memver_1")).thenReturn(Optional.of(redacted));

        // when
        MemoryVersion result = service.redactVersion(new RedactMemoryVersionCommand("ms_1", "memver_1"));

        // then
        assertEquals(redacted, result);
        verify(memoryRepository, never()).redactVersion(anyString(), any());
    }

    @Test
    void should_throwNotFound_when_redactVersion_given_versionOfAnotherStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        MemoryVersion foreign = MemoryVersion.created("memver_1", "ms_other", "mem_1", "notes/a", 1, "x");
        when(memoryRepository.findVersionForUpdate("memver_1")).thenReturn(Optional.of(foreign));

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.redactVersion(new RedactMemoryVersionCommand("ms_1", "memver_1")));
    }

    // ===== 写入守卫与并发竞态补充用例 =====

    @Test
    void should_throwConflictWithoutEntryLookup_when_updateEntry_given_archivedStore() {
        // given（归档库写守卫先于条目锁行查询生效）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1, null)));
        verify(memoryRepository, never()).findByMemoryIdForUpdate(anyString());
    }

    @Test
    void should_throwConflictWithoutTombstone_when_deleteEntry_given_archivedStore() {
        // given
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.deleteEntry("ms_1", "mem_1"));
        verify(memoryRepository, never()).deleteEntry(anyString(), any());
    }

    @Test
    void should_propagateUniqueConstraintWithoutStats_when_createEntry_given_duplicatePathRace() {
        // given（同库并发重复 path：部分唯一索引拦截，仓储抛出 DuplicateKeyException）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.createEntry(any(), any()))
                .thenThrow(new DuplicateKeyException("uk_memories_store_path"));

        // when // then（异常透出交全局异常处理器转 409；统计不得递增）
        assertThrows(DuplicateKeyException.class,
                () -> service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", "内容", null)));
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_throwNotFoundWithoutReferenceCheck_when_delete_given_otherOwnerStore() {
        // given（非 owner：锁行守卫 404，不泄露存在性，也不触达引用统计）
        OffsetDateTime now = OffsetDateTime.now();
        MemoryStore other = new MemoryStore(1L, "ms_1", "别人", "", MemoryStoreStatus.ACTIVE,
                0, 0L, 99L, null, now, now, null, null);
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1")).thenReturn(Optional.of(other));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.delete("ms_1"));
        verify(sessionReferenceApi, never()).countSessionsByMemoryStoreId(anyString());
        verify(memoryStoreRepository, never()).deleteByStoreId(anyString());
    }

    @Test
    void should_throwNotFoundWithoutStats_when_deleteEntry_given_casDeleteMiss() {
        // given（锁行时存在、墓碑落写前被并发删除：仓储返回 false 的竞态分支）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.findByMemoryIdForUpdate("mem_1")).thenReturn(Optional.of(buildMemory(3, 6L)));
        when(memoryRepository.deleteEntry(eq("mem_1"), any())).thenReturn(false);

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteEntry("ms_1", "mem_1"));
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_throwConflictAndRollback_when_createEntry_given_archivedVisibleAtLockRow() {
        // given（TOCTOU：事务外快速失败读到 active，归档事务先提交；事务内 FOR UPDATE 锁行复检读到 archived）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then（锁行复检 409：条目 / 版本不落库、统计不递增——真实链路随事务整体回滚）
        assertThrows(ResourceConflictException.class,
                () -> service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", "内容", null)));
        verify(memoryRepository, never()).createEntry(any(), any());
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_throwConflictBeforeEntryLock_when_updateEntry_given_archivedVisibleAtLockRow() {
        // given（TOCTOU：事务外读到 active，归档先提交；锁行复检为事务内首行，读到 archived）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then（409 先于条目锁行查询：不发生条目更新 / 版本追加 / 统计调整，随事务回滚）
        assertThrows(ResourceConflictException.class, () -> service.updateEntry(
                new UpdateMemoryEntryCommand("ms_1", "mem_1", "新内容", 1, null)));
        verify(memoryRepository, never()).findByMemoryIdForUpdate(anyString());
        verify(memoryRepository, never()).updateEntry(any(), anyInt(), any());
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_throwConflictBeforeEntryLock_when_deleteEntry_given_archivedVisibleAtLockRow() {
        // given（TOCTOU：事务外读到 active，归档先提交；锁行复检为事务内首行，读到 archived）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryStoreRepository.findByStoreIdForUpdate("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));

        // when // then（409 先于条目锁行查询：不落墓碑版本 / 不回退统计，随事务回滚）
        assertThrows(ResourceConflictException.class, () -> service.deleteEntry("ms_1", "mem_1"));
        verify(memoryRepository, never()).findByMemoryIdForUpdate(anyString());
        verify(memoryRepository, never()).deleteEntry(anyString(), any());
        verify(memoryStoreRepository, never()).adjustStats(anyString(), anyInt(), anyLong());
    }

    @Test
    void should_commitEntryWrite_when_createEntry_given_activeVisibleAtLockRow() {
        // given（写入先提交：锁行复检读到 active（归档尚未发生），写入正常落库；归档随后生效不回溯已提交数据）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ACTIVE)));
        when(memoryRepository.createEntry(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        long contentBytes = "内容".getBytes(StandardCharsets.UTF_8).length;

        // when（锁行读到 active，写入事务提交）
        MemoryDetail detail = service.createEntry(new CreateMemoryEntryCommand("ms_1", "notes/a", "内容", null));

        // then（条目 / 版本 / 统计均正常落库——已提交写入不被随后归档回滚；锁行复检确经 FOR UPDATE 读发生）
        verify(memoryStoreRepository).findByStoreIdForUpdate("ms_1");
        verify(memoryRepository).createEntry(any(), any());
        verify(memoryStoreRepository).adjustStats("ms_1", 1, contentBytes);
        assertEquals("内容", detail.content());
    }

    @Test
    void should_returnRedactedSnapshot_when_getVersion_given_archivedStore() {
        // given（读路径归档放行；已脱敏版本 content 为 null，服务不重建内容）
        when(memoryStoreRepository.findByStoreId("ms_1"))
                .thenReturn(Optional.of(buildStore("ms_1", MemoryStoreStatus.ARCHIVED)));
        MemoryVersion redacted =
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "敏感").redact();
        when(memoryRepository.findVersion("memver_1")).thenReturn(Optional.of(redacted));

        // when
        MemoryVersion result = service.getVersion("ms_1", "memver_1");

        // then（"敏感" UTF-8 为 6 字节：脱敏后字节数保留）
        assertTrue(result.redacted());
        assertNull(result.content());
        assertEquals(6L, result.size());
    }
}
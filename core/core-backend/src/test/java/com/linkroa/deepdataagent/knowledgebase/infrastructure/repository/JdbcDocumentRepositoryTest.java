package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.DocumentEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcDocumentRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcDocumentRepositoryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @Mock
    private DocumentMapper mapper;

    @InjectMocks
    private JdbcDocumentRepository repository;

    private static DocumentEntity entityOf(Long id, Long kbId, String fileName, String status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setKbId(kbId);
        entity.setFileName(fileName);
        entity.setFileType("PDF");
        entity.setStatus(status);
        entity.setFileSize(1024L);
        entity.setChunkCount(4);
        entity.setImportType("UPLOAD");
        entity.setSourceFileProfile("{\"pageCount\":2}");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    private static Document aggregateOf(Long id, DocumentStatus status) {
        return Document.restore(id, 1L, "手册.pdf", FileType.PDF, status, null, 1024L, 4,
                "{\"pageCount\":2}", ImportType.UPLOAD, null, null, null, NOW, NOW);
    }

    @Test
    void should_returnPersistedAggregate_when_save_given_newDocumentWithoutId() {
        // given
        Document document = Document.create(1L, "手册.pdf", FileType.PDF, 1024L,
                "{\"pageCount\":2}", null, null, null);
        doAnswer(invocation -> {
            DocumentEntity argument = invocation.getArgument(0);
            assertNull(argument.getId());
            argument.setId(200L);
            return 1;
        }).when(mapper).insert(any(DocumentEntity.class));
        when(mapper.selectById(200L)).thenReturn(entityOf(200L, 1L, "手册.pdf", "PENDING"));

        // when
        Document saved = repository.save(document);

        // then
        assertEquals(200L, saved.id());
        assertEquals(DocumentStatus.PENDING, saved.status());
        verify(mapper, never()).updateById(any(DocumentEntity.class));
    }

    @Test
    void should_callUpdateById_when_update_given_existingDocument() {
        // given
        Document document = aggregateOf(11L, DocumentStatus.PROCESSING);
        when(mapper.selectById(11L)).thenReturn(entityOf(11L, 1L, "手册.pdf", "PROCESSING"));

        // when
        Document updated = repository.update(document);

        // then
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("PROCESSING", captor.getValue().getStatus());
        verify(mapper, never()).insert(any(DocumentEntity.class));
        assertEquals(11L, updated.id());
    }

    @Test
    void should_returnEmpty_when_findById_given_noRecord() {
        // given
        when(mapper.selectById(404L)).thenReturn(null);

        // when
        Optional<Document> found = repository.findById(404L);

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void should_useForUpdateQuery_when_findByIdForUpdate_given_lockedRecord() {
        // given
        when(mapper.selectByIdForUpdate(12L)).thenReturn(entityOf(12L, 1L, "锁定.pdf", "PROCESSING"));

        // when
        Optional<Document> found = repository.findByIdForUpdate(12L);

        // then
        assertTrue(found.isPresent());
        assertEquals(FileType.PDF, found.get().fileType());
        verify(mapper, never()).selectById(12L);
    }

    @Test
    void should_convertPageToOffset_when_findByKbId_given_statusAndPage() {
        // given
        when(mapper.selectByKbId(1L, "手册", "PROCESSED", 10L, 5))
                .thenReturn(List.of(entityOf(21L, 1L, "手册.pdf", "PROCESSED")));

        // when
        List<Document> found = repository.findByKbId(1L, "手册", DocumentStatus.PROCESSED, 3, 5);

        // then
        assertEquals(1, found.size());
        assertEquals(21L, found.get(0).id());
        verify(mapper).selectByKbId(1L, "手册", "PROCESSED", 10L, 5);
    }

    @Test
    void should_passNullStatus_when_countByKbId_given_nullStatus() {
        // given
        when(mapper.countByKbId(1L, null, null)).thenReturn(7L);

        // when
        long total = repository.countByKbId(1L, null, null);

        // then
        assertEquals(7L, total);
        verify(mapper).countByKbId(1L, null, null);
    }

    @Test
    void should_mapDuplicates_when_findDuplicates_given_fileNameAndHash() {
        // given
        when(mapper.selectDuplicates(1L, "手册.pdf", "hash-1"))
                .thenReturn(List.of(entityOf(31L, 1L, "手册.pdf", "PROCESSED"),
                        entityOf(32L, 1L, "手册.pdf", "FAILED")));

        // when
        List<Document> duplicates = repository.findDuplicates(1L, "手册.pdf", "hash-1");

        // then
        assertEquals(2, duplicates.size());
        assertEquals(31L, duplicates.get(0).id());
        assertEquals(DocumentStatus.FAILED, duplicates.get(1).status());
    }

    @Test
    void should_delegateCountOnly_when_countByKbIdOnly_given_kbId() {
        // given
        when(mapper.countByKbIdOnly(1L)).thenReturn(3L);

        // when
        long total = repository.countByKbIdOnly(1L);

        // then
        assertEquals(3L, total);
    }

    @Test
    void should_delegateDeletes_when_deleteByIdAndDeleteByKbId_given_ids() {
        // when
        repository.deleteById(41L);
        repository.deleteByKbId(1L);

        // then
        verify(mapper).deleteById(41L);
        verify(mapper).deleteByKbId(1L);
    }

    @Test
    void should_returnTrue_when_transitStatus_given_casHit() {
        // given
        when(mapper.transitStatus(eq(51L), anyCollection(), eq("PROCESSING"), isNull())).thenReturn(1);

        // when
        boolean transited = repository.transitStatus(51L,
                Set.of(DocumentStatus.PENDING, DocumentStatus.FAILED), DocumentStatus.PROCESSING, null);

        // then
        assertTrue(transited);
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(mapper).transitStatus(eq(51L), captor.capture(), eq("PROCESSING"), isNull());
        assertEquals(2, captor.getValue().size());
        assertTrue(captor.getValue().containsAll(List.of("PENDING", "FAILED")));
    }

    @Test
    void should_writeErrorMessage_when_transitStatus_given_failedTransition() {
        // given
        when(mapper.transitStatus(52L, List.of("PROCESSING"), "FAILED", "解析超时")).thenReturn(1);

        // when
        boolean transited = repository.transitStatus(52L,
                Set.of(DocumentStatus.PROCESSING), DocumentStatus.FAILED, "解析超时");

        // then
        assertTrue(transited);
        verify(mapper).transitStatus(52L, List.of("PROCESSING"), "FAILED", "解析超时");
    }

    @Test
    void should_returnFalse_when_transitStatus_given_casMiss() {
        // given
        when(mapper.transitStatus(eq(53L), anyCollection(), eq("PROCESSING"), isNull())).thenReturn(0);

        // when
        boolean transited = repository.transitStatus(53L,
                Set.of(DocumentStatus.PENDING, DocumentStatus.FAILED), DocumentStatus.PROCESSING, null);

        // then
        assertFalse(transited);
    }

    @Test
    void should_returnFalseWithoutSql_when_transitStatus_given_illegalArguments() {
        // when
        boolean byNullId = repository.transitStatus(null,
                Set.of(DocumentStatus.PENDING), DocumentStatus.PROCESSING, null);
        boolean byEmptyFrom = repository.transitStatus(54L,
                Set.of(), DocumentStatus.PROCESSING, null);
        boolean byNullTo = repository.transitStatus(54L,
                Set.of(DocumentStatus.PENDING), null, null);

        // then
        assertFalse(byNullId);
        assertFalse(byEmptyFrom);
        assertFalse(byNullTo);
        verifyNoInteractions(mapper);
    }

    @Test
    void should_delegateFailNonTerminal_when_failNonTerminal_given_fromStatusesAndMessage() {
        // given
        when(mapper.failNonTerminal(anyCollection(), eq("FAILED"), eq("[STARTUP] 服务重启中断，请重新解析")))
                .thenReturn(3);

        // when
        int failed = repository.failNonTerminal(
                Set.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING),
                DocumentStatus.FAILED, "[STARTUP] 服务重启中断，请重新解析");

        // then：枚举按名称透传给 Mapper（Set 迭代序不定，按内容校验），影响行数原样返回
        assertEquals(3, failed);
        ArgumentCaptor<Collection<String>> fromCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(mapper).failNonTerminal(fromCaptor.capture(), eq("FAILED"),
                eq("[STARTUP] 服务重启中断，请重新解析"));
        assertEquals(Set.of("PENDING", "PROCESSING"), Set.copyOf(fromCaptor.getValue()));
    }

    @Test
    void should_returnZeroWithoutSql_when_failNonTerminal_given_blankArguments() {
        // when
        int byEmptyFrom = repository.failNonTerminal(Set.of(), DocumentStatus.FAILED, "启动清理");
        int byNullTo = repository.failNonTerminal(Set.of(DocumentStatus.PENDING), null, "启动清理");
        int byBlankMessage = repository.failNonTerminal(
                Set.of(DocumentStatus.PENDING), DocumentStatus.FAILED, " ");

        // then：非法入参直接返回 0，不触达 Mapper
        assertEquals(0, byEmptyFrom);
        assertEquals(0, byNullTo);
        assertEquals(0, byBlankMessage);
        verifyNoInteractions(mapper);
    }
}

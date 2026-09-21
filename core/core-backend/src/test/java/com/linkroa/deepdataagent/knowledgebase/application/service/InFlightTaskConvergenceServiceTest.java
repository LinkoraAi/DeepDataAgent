package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InFlightTaskConvergenceService} 在飞任务收敛原语单测。
 * <p>三个原语（解析收敛 / 文档清退收敛 / 整库清退收敛）共用同一语义：先读数据库权威状态并与在飞态比对，
 * 仅当状态一致时才条件置态；不一致或行不存在时零写入返回 {@code false}；入参为空时零 DB 交互返回
 * {@code false}。本类按「状态一致且条件置态命中」「状态不一致（已是终态）」「入参为空」「行不存在」
 * 四类分支逐一覆盖，断言意图迁自已删除的应用服务 converge 用例。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class InFlightTaskConvergenceServiceTest {

    /** 测试用文档ID */
    private static final Long DOC_ID = 100L;

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 在飞收敛的失败留痕（透传断言用，短文案不被截断） */
    private static final String CONVERGE_REASON = "[RESTART] 悬挂任务收敛";

    /** 在飞解析状态集合（与生产常量同口径，按内容等值校验） */
    private static final Set<DocumentStatus> INGESTION_IN_FLIGHT_STATUSES =
            Set.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING);

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private InFlightTaskConvergenceService service;

    // ==================== convergeIngestion：解析收敛 ====================

    @Test
    void should_returnTrue_when_convergeIngestion_given_pendingDocument() {
        // given：库内权威状态为 PENDING（在飞解析态），条件流转命中
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DocumentStatus.PENDING)));
        when(documentRepository.transitStatus(DOC_ID, INGESTION_IN_FLIGHT_STATUSES,
                DocumentStatus.FAILED, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = service.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then：状态一致 → 条件流转置 FAILED，不走清退侧的 markFailed
        assertTrue(converged);
        verify(documentRepository).transitStatus(DOC_ID, INGESTION_IN_FLIGHT_STATUSES,
                DocumentStatus.FAILED, CONVERGE_REASON);
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_returnFalse_when_convergeIngestion_given_processedDocument() {
        // given：解析已成功收敛（PROCESSED 为终态，MUST NOT 被反写为 FAILED）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED)));

        // when
        boolean converged = service.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then：状态不一致 → 零数据库写操作
        assertFalse(converged);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_notOverwriteDeleting_when_convergeIngestion_given_deletingDocument() {
        // given：文档已被用户受理进入删除链（DELETING 不属在飞解析态，
        // MUST NOT 被反写为 FAILED 而破坏删除链）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DocumentStatus.DELETING)));

        // when
        boolean converged = service.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then：状态不一致 → 零数据库写操作
        assertFalse(converged);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_returnFalse_when_convergeIngestion_given_nullDocumentId() {
        // given：文档ID为空
        // when
        boolean converged = service.convergeIngestion(null, CONVERGE_REASON);

        // then：入参为空即视为不一致，零 DB 交互
        assertFalse(converged);
        verifyNoInteractions(documentRepository, knowledgeBaseRepository);
    }

    @Test
    void should_returnFalse_when_convergeIngestion_given_documentRowMissing() {
        // given：行已物理缺失（已收口 / 从未存在）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when
        boolean converged = service.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then：行不存在 → 零数据库写操作
        assertFalse(converged);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    // ==================== convergeDocumentCleanup：文档清退收敛 ====================

    @Test
    void should_returnTrue_when_convergeDocumentCleanup_given_deletingDocument() {
        // given：文档处于清退在飞态 DELETING，条件置态命中
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DocumentStatus.DELETING)));
        when(documentRepository.markFailed(DOC_ID, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = service.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);

        // then：状态一致 → 条件置 DELETE_FAILED 并留痕，不走解析侧的 transitStatus
        assertTrue(converged);
        verify(documentRepository).markFailed(DOC_ID, CONVERGE_REASON);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    void should_returnFalse_when_convergeDocumentCleanup_given_deleteFailedDocument() {
        // given：清退已失败留痕（DELETE_FAILED 为删除链已收敛终态，MUST NOT 覆盖既有留痕）
        Document failed = buildDocument(DocumentStatus.DELETE_FAILED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(failed));

        // when
        boolean converged = service.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);

        // then：状态不一致 → 零数据库写操作
        assertFalse(converged);
        verify(documentRepository, never()).markFailed(any(), any());
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    void should_returnFalse_when_convergeDocumentCleanup_given_nullDocumentId() {
        // given：文档ID为空
        // when
        boolean converged = service.convergeDocumentCleanup(null, CONVERGE_REASON);

        // then：入参为空即视为不一致，零 DB 交互
        assertFalse(converged);
        verifyNoInteractions(documentRepository, knowledgeBaseRepository);
    }

    @Test
    void should_returnFalse_when_convergeDocumentCleanup_given_documentRowMissing() {
        // given：行已物理缺失（已收口 / 从未存在）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when
        boolean converged = service.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);

        // then：行不存在 → 零数据库写操作
        assertFalse(converged);
        verify(documentRepository, never()).markFailed(any(), any());
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    // ==================== convergeKbCleanup：整库清退收敛 ====================

    @Test
    void should_returnTrue_when_convergeKbCleanup_given_deletingKnowledgeBase() {
        // given：库处于清退在飞态 DELETING，条件置态命中
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));
        when(knowledgeBaseRepository.markFailed(KB_ID, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = service.convergeKbCleanup(KB_ID, CONVERGE_REASON);

        // then：状态一致 → 条件置 DELETE_FAILED 并留痕，不触碰文档仓储
        assertTrue(converged);
        verify(knowledgeBaseRepository).markFailed(KB_ID, CONVERGE_REASON);
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
        verifyNoInteractions(documentRepository);
    }

    @Test
    void should_returnFalse_when_convergeKbCleanup_given_deleteFailedKnowledgeBase() {
        // given：库已 DELETE_FAILED（清退已收敛终态，MUST NOT 覆盖既有留痕）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETE_FAILED)));

        // when
        boolean converged = service.convergeKbCleanup(KB_ID, CONVERGE_REASON);

        // then：状态不一致 → 零数据库写操作
        assertFalse(converged);
        verify(knowledgeBaseRepository, never()).markFailed(any(), any());
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
    }

    @Test
    void should_returnFalse_when_convergeKbCleanup_given_nullKbId() {
        // given：知识库ID为空
        // when
        boolean converged = service.convergeKbCleanup(null, CONVERGE_REASON);

        // then：入参为空即视为不一致，零 DB 交互
        assertFalse(converged);
        verifyNoInteractions(documentRepository, knowledgeBaseRepository);
    }

    @Test
    void should_returnFalse_when_convergeKbCleanup_given_knowledgeBaseRowMissing() {
        // given：行已物理缺失（已收口 / 从未存在）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when
        boolean converged = service.convergeKbCleanup(KB_ID, CONVERGE_REASON);

        // then：行不存在 → 零数据库写操作
        assertFalse(converged);
        verify(knowledgeBaseRepository, never()).markFailed(any(), any());
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
    }

    /**
     * 构造指定处理状态的文档（测试数据显式、局部）。
     *
     * @param status 文档处理状态
     * @return 携带固定主键与知识库归属的文档实例
     */
    private Document buildDocument(DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, KB_ID, "手册.pdf", FileType.PDF, status, null, 1024L, 3,
                null, ImportType.UPLOAD, null, null, null, now, now);
    }

    /**
     * 构造指定生命周期状态的知识库（测试数据显式、局部）。
     *
     * @param status 知识库生命周期状态
     * @return 携带固定主键的知识库实例
     */
    private KnowledgeBase buildKb(LifecycleStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "原始描述", "Chinese", status, null,
                null, null, null, null, null, null, now, now);
    }
}
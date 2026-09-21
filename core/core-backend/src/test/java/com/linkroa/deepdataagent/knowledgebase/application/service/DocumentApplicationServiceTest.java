package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentCleanupTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionCancellationApi;
import com.linkroa.deepdataagent.knowledgebase.api.IngestionTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.ReparseDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UploadDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListDocumentQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.application.result.DocumentContentResult;
import com.linkroa.deepdataagent.knowledgebase.application.result.UploadDocumentResult;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DedupPolicyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupConflictAction;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupMatchRule;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentApplicationService} 单元测试。
 * <p>口径说明：对象存储迁移后统一经业务端口 {@link KbAssetStoragePort} 按对象键读写
 * （桶概念已退役）——上传写入走 {@code putSource}、原文代理走 {@code open}
 * （对象缺失返回 404「文档源文件对象不存在」）、删除链资产回收走
 * {@code cleanupPrefix}（文档媒体前缀整体清退）+ {@code delete}（源文件定点回收）。
 * 旧「桶先查后建（bucketExists/createBucket）」与「headObject 元数据 contentType 优先」
 * 能力已随迁移消失，对应用例删除，contentType 改为按文档登记的 FileType 映射。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentApplicationServiceTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 测试用文档ID（覆盖换版场景下作为被清退的旧文档ID） */
    private static final Long DOC_ID = 100L;

    /** 覆盖换版场景下本次新登记文档的ID（与旧文档ID区隔，便于断言双投递的目标各属其主） */
    private static final Long NEW_DOC_ID = 200L;

    /** 知识库 RAG 引擎配置（含分块策略子节点） */
    private static final String KB_ENGINE_CONFIG =
            "{\"parseEngine\":\"pandoc\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"chunkSize\":512}}}";

    /** 从 KB_ENGINE_CONFIG 中提取出的分块策略快照 */
    private static final String KB_CHUNK_STRATEGY_SNAPSHOT =
            "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"chunkSize\":512}}";

    /** 重新解析时传入的分块策略覆盖值 */
    private static final String CHUNK_STRATEGY_OVERRIDE =
            "{\"chunkMode\":\"QA\",\"modeConfig\":{\"chunkSize\":256}}";

    /** 上传文件字节（测试载荷） */
    private static final byte[] FILE_BYTES = new byte[] {1, 2, 3, 4};

    /** 服务端实算内容哈希桩值 */
    private static final String SERVER_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private DocumentDedupService documentDedupService;
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private IngestionCancellationApi ingestionCancellationApi;
    @Mock
    private IngestionTaskSubmitter ingestionTaskSubmitter;
    @Mock
    private ChunkPhysicalDeleteService chunkPhysicalDeleteService;
    @Mock
    private DocumentCleanupTaskSubmitter documentCleanupTaskSubmitter;
    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    @InjectMocks
    private DocumentApplicationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxFileSize", DataSize.ofMegabytes(100));
        ReflectionTestUtils.setField(service, "deleteCleanupBatchSize", 500);
        ReflectionTestUtils.setField(service, "cancelWaitTimeoutSeconds", 30L);
        // 媒体图片清理原语已下沉至 MediaImageCleanupService：
        // 注入包裹本 mock 存储端口的真实实例，使删除链 / 换版链用例仍端到端断言
        // 「文档媒体前缀单次清退 + 源文件对象定点幂等删除」的新契约口径
        ReflectionTestUtils.setField(service, "mediaImageCleanupService",
                new MediaImageCleanupService(kbAssetStoragePort));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // 解析任务提交契约默认成功入队：
        // 拒收 / 抛异常的回滚分支由专项用例覆写本桩
        lenient().when(ingestionTaskSubmitter.submit(any())).thenReturn(true);
    }

    private KnowledgeBase buildKb(LifecycleStatus status) {
        return buildKb(status, null);
    }

    private KnowledgeBase buildKb(LifecycleStatus status, String ragEngineConfig) {
        return buildKb(status, ragEngineConfig, null);
    }

    private KnowledgeBase buildKb(LifecycleStatus status, String ragEngineConfig, String dedupPolicy) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", status,
                null, ragEngineConfig, dedupPolicy, null, null, null, null, now, now);
    }

    private Document buildDocument(DocumentStatus status, int chunkCount) {
        return buildDocument(status, chunkCount, null);
    }

    private Document buildDocument(DocumentStatus status, int chunkCount, String chunkStrategy) {
        return buildDocument(status, chunkCount, chunkStrategy, null);
    }

    private Document buildDocument(DocumentStatus status, int chunkCount, String chunkStrategy, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, KB_ID, "手册.pdf", FileType.PDF, status, errorMessage, 1024L, chunkCount,
                null, ImportType.UPLOAD, null, null, chunkStrategy, now, now);
    }

    private Document buildDocumentWithId(Long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(id, KB_ID, "手册.pdf", FileType.PDF, DocumentStatus.PROCESSED, null, 1024L, 3,
                null, ImportType.UPLOAD, null, null, null, now, now);
    }

    /**
     * 构造上传命令（默认载荷字节）。
     *
     * @param fileType 文件格式取值
     * @return 上传命令
     */
    private UploadDocumentCommand uploadCommand(String fileType) {
        return uploadCommand(fileType, FILE_BYTES);
    }

    /**
     * 构造上传命令。
     *
     * @param fileType 文件格式取值
     * @param content  文件字节
     * @return 上传命令
     */
    private UploadDocumentCommand uploadCommand(String fileType, byte[] content) {
        return new UploadDocumentCommand(KB_ID, "手册.pdf", fileType, "application/pdf",
                "{\"pages\":3}", null, content);
    }

    /**
     * 构造携带文档级分块策略的上传命令（默认载荷字节）。
     *
     * @param chunkStrategy 文档级分块策略 JSON
     * @return 上传命令
     */
    private UploadDocumentCommand uploadCommandWithStrategy(String chunkStrategy) {
        return new UploadDocumentCommand(KB_ID, "手册.pdf", "PDF", "application/pdf",
                "{\"pages\":3}", chunkStrategy, FILE_BYTES);
    }

    /**
     * 装配上传前置桩：知识库可写（含锁读）→ 服务端哈希（桶存在性检查已随桶概念退役）。
     *
     * @param knowledgeBase 知识库聚合根
     */
    private void stubUploadPremises(KnowledgeBase knowledgeBase) {
        lenient().when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(knowledgeBase));
        lenient().when(knowledgeBaseRepository.findByIdForUpdate(KB_ID)).thenReturn(Optional.of(knowledgeBase));
        lenient().when(documentDedupService.sha256Hex(any())).thenReturn(SERVER_HASH);
    }

    /**
     * 捕获本次上传实际写入对象存储的对象键。
     *
     * @return 对象键
     */
    private String capturedStoredObjectKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kbAssetStoragePort).putSource(keyCaptor.capture(), any(byte[].class), any());
        return keyCaptor.getValue();
    }

    /**
     * 断言刚写入的源文件对象已被补偿删除（回收的对象键与写入的对象键一致，不误删他人字节）。
     */
    private void verifyStoredObjectRecycled() {
        // 先完成 putSource 的参数捕获校验，再发起 delete 校验：
        // 嵌套在 verify(...) 实参位求值的第二个 verify 会触发 Mockito UnfinishedVerification
        String storedObjectKey = capturedStoredObjectKey();
        verify(kbAssetStoragePort).delete(storedObjectKey);
    }

    /**
     * 断言源文件对象未被清理（登记已发生，对象被新文档行引用）。
     */
    private void verifyStoredObjectKept() {
        verify(kbAssetStoragePort, never()).delete(anyString());
    }

    @Test
    void should_savePendingDocumentWithServerDerivedReference_when_upload_given_activeKnowledgeBase() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：大小与源文件引用取服务端实写值，而非调用方自报
        assertFalse(result.skipped());
        Document saved = result.document();
        assertEquals(KB_ID, saved.kbId());
        assertEquals(FileType.PDF, saved.fileType());
        assertEquals(DocumentStatus.PENDING, saved.status());
        assertEquals(0, saved.chunkCount().intValue());
        assertEquals((long) FILE_BYTES.length, saved.fileSize().longValue());
        // 源文件引用 JSON 仅对象键单分量（桶概念已退役）
        assertTrue(saved.s3File().startsWith("{\"objectKey\":\"rag/" + KB_ID + "/source/"));
    }

    @Test
    void should_submitIngestionTaskWithoutRollback_when_upload_given_newDocumentRegistered() {
        // given：登记成功返回带 ID 的 PENDING 文档，提交契约默认入队成功（setUp 桩）。
        // 契约语义收紧（fix-rag-concurrency-integrity）后，true 恒表达「该文档后续确实会被处理」
        // ——真实入队或已登记待补投，KB 侧「返回 true 即视为已受理」的判定口径不变
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：事务提交后按文档ID入队一次，无失败回滚流转；在飞凭据保留（由队列侧后续收敛）
        assertFalse(result.skipped());
        verify(ingestionTaskSubmitter).submit(DOC_ID);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    @Test
    void should_rollbackToFailed_when_upload_given_enqueueRejected() {
        // given：队列拒收（如停机边界），PENDING 孤儿窗口必须回滚闭合
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        when(ingestionTaskSubmitter.submit(DOC_ID)).thenReturn(false);

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：回写「解析任务提交失败」，上传响应不回退
        assertFalse(result.skipped());
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败");
    }

    @Test
    void should_rollbackToFailed_when_upload_given_enqueueThrows() {
        // given：契约实现抛异常（停机拒提 IllegalStateException 等）同样触发回滚
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        when(ingestionTaskSubmitter.submit(DOC_ID)).thenThrow(new IllegalStateException("摄入队列已停机"));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：异常被消化，回滚流转照常执行，响应不受影响
        assertFalse(result.skipped());
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败");
    }

    @Test
    void should_notSubmitIngestionTask_when_upload_given_duplicateSkipped() {
        // given：判重跳过（返回既有文档，未新登记）不产生新的解析任务
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"SKIP\"}");
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.SKIP);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any()))
                .thenReturn(List.of(new DedupHit(buildDocumentWithId(DOC_ID), List.of("fileName"))));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：跳过路径不入队、不回滚
        assertTrue(result.skipped());
        verifyNoInteractions(ingestionTaskSubmitter);
    }

    @Test
    void should_submitIngestionTaskAgain_when_reparse_given_resetSucceeded() {
        // given：重新解析事务把文档重置回 PENDING（已入队语义）
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 8)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Document reset = service.reparse(new ReparseDocumentCommand(DOC_ID, null));

        // then：事务提交后再次入队，无失败回滚
        assertEquals(DocumentStatus.PENDING, reset.status());
        verify(ingestionTaskSubmitter).submit(DOC_ID);
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    void should_throwNotFoundWithoutStoring_when_upload_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.upload(uploadCommand("PDF")));
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_upload_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.upload(uploadCommand("PDF")));
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
    }

    @Test
    void should_throwBadRequest_when_upload_given_unsupportedFileType() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.upload(uploadCommand("exe")));
        verify(documentRepository, never()).save(any());
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
    }

    @Test
    void should_throwBadRequestWithoutResidualObject_when_upload_given_oversizeFile() {
        // given：上限收缩到 4 字节，提交 5 字节文件
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        ReflectionTestUtils.setField(service, "maxFileSize", DataSize.ofBytes(4));

        // when // then：登记前拒绝，对象存储零交互（无残留）、库内无新文档
        assertThrows(DeepDataAgentException.class, () -> service.upload(uploadCommand("PDF", new byte[5])));
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_upload_given_emptyFileBytes() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.upload(uploadCommand("PDF", new byte[0])));
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void should_storeFileOutsideTransactionBeforeKnowledgeBaseLock_when_upload_given_validFile() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.upload(uploadCommand("PDF"));

        // then：落文件（远程 IO）在事务外先完成，事务首步锁知识库行，随后判重与登记
        InOrder inOrder = inOrder(kbAssetStoragePort, knowledgeBaseRepository, documentDedupService,
                documentRepository);
        inOrder.verify(kbAssetStoragePort).putSource(anyString(), any(byte[].class), any());
        inOrder.verify(knowledgeBaseRepository).findByIdForUpdate(KB_ID);
        inOrder.verify(documentDedupService).detect(eq(KB_ID), any(), any(), any());
        inOrder.verify(documentRepository).save(any());
    }

    @Test
    void should_useServerHashOfActualBytes_when_upload_given_fileBytes() throws Exception {
        // given：判重内容轴与落库内容轴均取服务端对上传的原始文件字节实算的哈希
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：哈希入口拿到的是本次收到的原始字节
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(documentDedupService).sha256Hex(bytesCaptor.capture());
        assertArrayEquals(FILE_BYTES, bytesCaptor.getValue());

        // then：写入对象存储的字节与判重字节同源
        ArgumentCaptor<byte[]> bytesCaptor2 = ArgumentCaptor.forClass(byte[].class);
        verify(kbAssetStoragePort).putSource(anyString(), bytesCaptor2.capture(), eq("application/pdf"));
        assertArrayEquals(FILE_BYTES, bytesCaptor2.getValue());

        // then：判重内容轴与文档行内容轴都带上服务端哈希（独立列直接承载，不再经 JSONB 载体包装）
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentDedupService).detect(eq(KB_ID), isNull(), eq("手册.pdf"), hashCaptor.capture());
        assertEquals(SERVER_HASH, hashCaptor.getValue());
        assertEquals(SERVER_HASH, result.document().fileContentHash());
    }

    @Test
    void should_buildSourceObjectKeyUnderKnowledgeBasePrefix_when_upload_given_pdfFile() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.upload(uploadCommand("PDF"));

        // then：对象键落在 rag/{kbId}/source/ 前缀下并带扩展名，整库清退可一并覆盖
        String objectKey = capturedStoredObjectKey();
        assertTrue(objectKey.startsWith("rag/" + KB_ID + "/source/"));
        assertTrue(objectKey.endsWith(".pdf"));
    }

    @Test
    void should_convertStatusEnum_when_list_given_statusKeyword() {
        // given
        List<Document> pageData = List.of(buildDocument(DocumentStatus.PROCESSED, 3));
        when(documentRepository.findByKbId(KB_ID, "手册", DocumentStatus.PROCESSED, 1, 20)).thenReturn(pageData);

        // when
        List<Document> result = service.list(new ListDocumentQuery(KB_ID, "手册", "PROCESSED", 1, 20));

        // then
        assertEquals(pageData, result);
        verify(documentRepository).findByKbId(KB_ID, "手册", DocumentStatus.PROCESSED, 1, 20);
    }

    @Test
    void should_throwBadRequest_when_count_given_illegalStatus() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.count(KB_ID, null, "RUNNING"));
    }

    @Test
    void should_returnCount_when_count_given_blankStatus() {
        // given
        when(documentRepository.countByKbId(KB_ID, null, null)).thenReturn(7L);

        // when
        long total = service.count(KB_ID, null, "  ");

        // then
        assertEquals(7L, total);
    }

    @Test
    void should_returnDocument_when_get_given_existingId() {
        // given
        Document existing = buildDocument(DocumentStatus.PROCESSED, 3);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(existing));

        // when
        Document result = service.get(DOC_ID);

        // then
        assertEquals(existing, result);
    }

    @Test
    void should_throwNotFound_when_get_given_missingId() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get(DOC_ID));
    }

    // ==================== 两段式删除链·受理段 ====================

    /** 受理 CAS 的五源态集（与私有常量 DELETE_ACCEPT_FROM_STATUSES 同口径，按内容等值校验） */
    private static final Set<DocumentStatus> ACCEPT_SOURCE_STATUSES = Set.of(
            DocumentStatus.PENDING, DocumentStatus.PROCESSING, DocumentStatus.PROCESSED,
            DocumentStatus.FAILED, DocumentStatus.DELETE_FAILED);

    /**
     * 装配受理段前置桩：锁读文档行 + CAS 命中 + 提交端口受理。
     *
     * @param document  锁内读到的文档行
     * @param submitted 提交端口返回值（true=已投递异步推进，false=在飞去重）
     */
    private void stubAcceptPremises(Document document, boolean submitted) {
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(document.status() != DocumentStatus.DELETING);
        lenient().when(documentCleanupTaskSubmitter.submit(eq(DOC_ID), any(Runnable.class))).thenReturn(submitted);
    }

    @Test
    void should_submitCleanupTaskWithoutRunningChain_when_delete_given_deletableDocument() {
        // given：PROCESSED 文档，CAS 命中，端口受理（返回 true）
        stubAcceptPremises(buildDocument(DocumentStatus.PROCESSED, 3), true);

        // when
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then：单语句 CAS 五源态→DELETING 且清除失败留痕；回显快照状态 DELETING
        verify(documentRepository).transitStatus(DOC_ID, ACCEPT_SOURCE_STATUSES, DocumentStatus.DELETING, null);
        assertEquals(DocumentStatus.DELETING, accepted.status());
        assertNull(accepted.errorMessage());

        // then：清退任务体经端口投递（fire-and-forget），请求线程不执行任何清退步骤
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), taskCaptor.capture());
        assertNotNull(taskCaptor.getValue());
        verifyNoInteractions(ingestionCancellationApi, chunkRepository, chunkPhysicalDeleteService,
                kbAssetStoragePort);
        verify(documentRepository, never()).executeDelete(any());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_acceptIdempotently_when_delete_given_inFlightCleanup() {
        // given：行已 DELETING（CAS 未命中）且清退任务在飞（端口去重回 false）
        stubAcceptPremises(buildDocument(DocumentStatus.DELETING, 3), false);

        // when：不抛错，按「删除中」幂等受理
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then
        assertEquals(DocumentStatus.DELETING, accepted.status());
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_retriggerAndAccept_when_delete_given_crashLeftoverDeletingRow() {
        // given：崩溃遗留 DELETING 行（线程已失），端口重触发成功回 true
        stubAcceptPremises(buildDocument(DocumentStatus.DELETING, 3), true);

        // when
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then：重触发已受理，快照仍为 DELETING
        assertEquals(DocumentStatus.DELETING, accepted.status());
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
    }

    @Test
    void should_clearFailureTraceOnRedelete_when_delete_given_deleteFailedDocument() {
        // given：DELETE_FAILED 重删——CAS 源态集含 DELETE_FAILED，errorMessage 传 null 即清留痕
        stubAcceptPremises(buildDocument(DocumentStatus.DELETE_FAILED, 3, null, "[DELETE-FAILED] step=media_cleanup"),
                true);

        // when
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then
        verify(documentRepository).transitStatus(DOC_ID, ACCEPT_SOURCE_STATUSES, DocumentStatus.DELETING, null);
        assertNull(accepted.errorMessage());
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
    }

    @Test
    void should_throwNotFoundWithoutSubmit_when_delete_given_documentRowMissing() {
        // given：行物理缺失（已收口的唯一表达）
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.empty());

        // when // then：404，零 CAS、零投递
        assertThrows(ResourceNotFoundException.class, () -> service.delete(new DeleteDocumentCommand(DOC_ID)));
        verify(documentRepository, never()).transitStatus(any(), any(), any(), any());
        verifyNoInteractions(documentCleanupTaskSubmitter);
    }

    @Test
    void should_throwConflictWithoutSubmit_when_delete_given_casMissedByConcurrentTransition() {
        // given：锁内行是 PROCESSED 但 CAS 未命中（持锁窗口状态被并发链改走的防御分支）
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 3)));
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(false);

        // when // then：409 冲突，不投递
        assertThrows(ResourceConflictException.class, () -> service.delete(new DeleteDocumentCommand(DOC_ID)));
        verifyNoInteractions(documentCleanupTaskSubmitter);
    }

    @Test
    void should_stillReturnAcceptedSnapshot_when_delete_given_executorShutdownRejectsSubmit() {
        // given：投递抛停机 IllegalStateException——行停留 DELETING，仅 ERROR 留痕，不改对外响应
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 3)));
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(true);
        when(documentCleanupTaskSubmitter.submit(eq(DOC_ID), any(Runnable.class)))
                .thenThrow(new IllegalStateException("删除清退执行器已停机"));

        // when
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then：受理结果不回滚、不落失败态
        assertEquals(DocumentStatus.DELETING, accepted.status());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    // ==================== 两段式删除链·清退任务体 ====================

    /**
     * 装配清退任务体前置桩（无媒体资产文档：对象存储零交互）：掐灭等待成功 + 首轮批取给定切片、次轮取空终止循环。
     *
     * @param firstBatchIds 首轮批取的切片ID列表（无残留切片时传空列表）
     */
    private void stubChainPremisesWithoutMedia(List<Long> firstBatchIds) {
        lenient().when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        lenient().when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.DELETING, 3)));
        lenient().when(chunkRepository.findIdsByDocumentId(DOC_ID, 500)).thenReturn(firstBatchIds, List.of());
    }

    @Test
    void should_runChainInOrderAndClosePhysically_when_runCleanupChain_given_documentWithChunksAndMedia() {
        // given：媒体前缀清退 + 源文件定点回收 + 一批切片 2 条，收口命中 1 行
        when(ingestionCancellationApi.awaitTermination(DOC_ID, Duration.ofSeconds(30))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithMedia()));
        String prefix = "rag/" + KB_ID + "/" + DOC_ID + "/images/";
        when(chunkRepository.findIdsByDocumentId(DOC_ID, 500)).thenReturn(List.of(1L, 2L), List.of());
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then：固定时序——掐灭等待 → Storage 资产回收（媒体前缀单次清退 → 源文件定点删除）
        // → 分批 DB 清退（复用切片原语：文档链跳过计数回写，图谱收敛由批次数据推导）→ 收口条件物理 DELETE
        InOrder inOrder = inOrder(ingestionCancellationApi, kbAssetStoragePort,
                chunkRepository, chunkPhysicalDeleteService, documentRepository);
        inOrder.verify(ingestionCancellationApi).awaitTermination(DOC_ID, Duration.ofSeconds(30));
        inOrder.verify(kbAssetStoragePort).cleanupPrefix(prefix);
        inOrder.verify(kbAssetStoragePort).delete(SOURCE_OBJECT_KEY);
        inOrder.verify(chunkRepository).findIdsByDocumentId(DOC_ID, 500);
        inOrder.verify(chunkPhysicalDeleteService).deleteChunks(List.of(1L, 2L), null, false);
        inOrder.verify(documentRepository).executeDelete(DOC_ID);
        // then：全程无失败留痕
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_markDeleteFailedWithCancelStep_when_runCleanupChain_given_cancelWaitThrows() {
        // given：掐灭等待步骤抛异常 → 终止本任务并留痕 step=cancel_wait
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class)))
                .thenThrow(new RuntimeException("等待被中断"));

        // when：任务体自带全量 catch，异常不逃逸（fire-and-forget 契约）
        service.runCleanupChain(DOC_ID);

        // then：零 Storage / 零 DB 清退、不收口，仅 DELETE_FAILED 留痕
        verify(kbAssetStoragePort, never()).cleanupPrefix(anyString());
        verify(chunkRepository, never()).findIdsByDocumentId(any(), anyInt());
        verify(documentRepository, never()).executeDelete(any());
        verify(documentRepository).markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=cancel_wait"));
    }

    @Test
    void should_markDeleteFailedWithMediaStepAndZeroDbCleanup_when_runCleanupChain_given_objectStorageUnreachable() {
        // given：Storage 先删——媒体前缀清退抛异常 → DB 清退一批都未发起（失败零写入）
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithMedia()));
        doThrow(new RuntimeException("对象存储不可达")).when(kbAssetStoragePort)
                .cleanupPrefix("rag/" + KB_ID + "/" + DOC_ID + "/images/");

        // when
        service.runCleanupChain(DOC_ID);

        // then：留痕 step=media_cleanup，不收口；重删全链幂等续跑（对象不存在=成功消化半态）
        verify(chunkRepository, never()).findIdsByDocumentId(any(), anyInt());
        verify(documentRepository, never()).executeDelete(any());
        verify(documentRepository).markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=media_cleanup"));
    }

    @Test
    void should_markDeleteFailedWithSourceStep_when_runCleanupChain_given_sourceFileDeleteFailure() {
        // given：媒体前缀清退通过（默认空操作），源文件定点删除抛异常
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithMedia()));
        doThrow(new RuntimeException("对象存储删除失败")).when(kbAssetStoragePort).delete(SOURCE_OBJECT_KEY);

        // when
        service.runCleanupChain(DOC_ID);

        // then：留痕 step=source_file_cleanup，批删 / 收口均不触达
        verify(chunkRepository, never()).findIdsByDocumentId(any(), anyInt());
        verify(documentRepository, never()).executeDelete(any());
        verify(documentRepository).markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=source_file_cleanup"));
    }

    @Test
    void should_markDeleteFailedWithBatchStep_when_runCleanupChain_given_batchCleanupFailure() {
        // given：无媒体交互；一批切片清退时原语抛异常 → 当批独立小事务回滚，已成功批次不回滚
        stubChainPremisesWithoutMedia(List.of(7L, 8L));
        doThrow(new RuntimeException("切片行删除失败"))
                .when(chunkPhysicalDeleteService).deleteChunks(eq(List.of(7L, 8L)), isNull(), eq(false));

        // when
        service.runCleanupChain(DOC_ID);

        // then：留痕携带失败步骤与本批切片ID，不收口
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).markFailed(eq(DOC_ID), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().startsWith("[DELETE-FAILED] step=chunk_batch_cleanup"));
        assertTrue(messageCaptor.getValue().contains("7, 8"));
        verify(documentRepository, never()).executeDelete(any());
    }

    @Test
    void should_closeWithoutBatch_when_runCleanupChain_given_noResidualChunks() {
        // given：重删续跑——上链已清空全部切片，批取首轮即空
        stubChainPremisesWithoutMedia(List.of());
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then：不发起原语批删，直接收口条件物理 DELETE（文档行物理消失）
        verify(chunkPhysicalDeleteService, never()).deleteChunks(any(), any(), anyBoolean());
        verify(documentRepository).executeDelete(DOC_ID);
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_finishIdempotentlyWithoutMarkFailed_when_runCleanupChain_given_closeZeroRowsAffected() {
        // given：收口条件 DELETE 命中 0 行（并发链已收口，行已物理不存在）
        stubChainPremisesWithoutMedia(List.of());
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(false);

        // when：0 行=幂等空转，MUST NOT 抛出、MUST NOT 留痕
        service.runCleanupChain(DOC_ID);

        // then
        verify(documentRepository).executeDelete(DOC_ID);
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_skipAssetCleanupQuietly_when_runCleanupChain_given_documentRowAlreadyClosed() {
        // given：任务体入口后文档行已被并发链收口（行缺失＝已删除唯一表达）
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        when(chunkRepository.findIdsByDocumentId(DOC_ID, 500)).thenReturn(List.of());

        // when：资产段 WARN 跳过（零对象存储交互），后续步骤按幂等空转推进
        service.runCleanupChain(DOC_ID);

        // then
        verify(kbAssetStoragePort, never()).cleanupPrefix(anyString());
        verify(kbAssetStoragePort, never()).delete(anyString());
        verify(documentRepository, never()).markFailed(any(), any());
        verify(documentRepository).executeDelete(DOC_ID);
    }

    @Test
    void should_resetPendingAndZeroChunk_when_reparse_given_processedDocument() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 8)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Document reset = service.reparse(new ReparseDocumentCommand(DOC_ID, null));

        // then
        assertEquals(DocumentStatus.PENDING, reset.status());
        assertEquals(0, reset.chunkCount().intValue());
    }

    @Test
    void should_clearErrorMessage_when_reparse_given_failedDocumentWithErrorMessage() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.FAILED, 0, null, "解析失败：向量模型超时")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Document reset = service.reparse(new ReparseDocumentCommand(DOC_ID, null));

        // then
        assertEquals(DocumentStatus.PENDING, reset.status());
        assertNull(reset.errorMessage());
    }

    @Test
    void should_refreshStrategyWithOverride_when_reparse_given_chunkStrategyOverride() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 8, "{\"chunkMode\":\"SPLIT\"}")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, KB_ENGINE_CONFIG)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.reparse(new ReparseDocumentCommand(DOC_ID, CHUNK_STRATEGY_OVERRIDE));

        // then
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(captor.capture());
        assertEquals(CHUNK_STRATEGY_OVERRIDE, captor.getValue().chunkStrategy());
    }

    @Test
    void should_fallbackKnowledgeBaseStrategy_when_reparse_given_blankOverride() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.FAILED, 0, "{\"chunkMode\":\"SPLIT\"}")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, KB_ENGINE_CONFIG)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.reparse(new ReparseDocumentCommand(DOC_ID, "  "));

        // then
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(captor.capture());
        assertEquals(KB_CHUNK_STRATEGY_SNAPSHOT, captor.getValue().chunkStrategy());
    }

    @Test
    void should_clearChunkStrategy_when_reparse_given_knowledgeBaseWithoutChunkStrategy() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 5, "{\"chunkMode\":\"SPLIT\"}")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, "{\"parseEngine\":\"pandoc\"}")));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.reparse(new ReparseDocumentCommand(DOC_ID, null));

        // then
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(captor.capture());
        assertNull(captor.getValue().chunkStrategy());
    }

    @Test
    void should_throwBadRequest_when_reparse_given_illegalKnowledgeBaseEngineConfig() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 5)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, "{illegal-json")));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.reparse(new ReparseDocumentCommand(DOC_ID, null)));
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequest_when_reparse_given_deletingKnowledgeBase() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.FAILED, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.reparse(new ReparseDocumentCommand(DOC_ID, null)));
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_throwConflict_when_reparse_given_processingDocument() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSING, 0)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.reparse(new ReparseDocumentCommand(DOC_ID, CHUNK_STRATEGY_OVERRIDE)));
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequestWithoutStoringObject_when_upload_given_illegalChunkStrategy() {
        // given：文档级分块策略把 token 预算写成 0（值域 (0,2048]）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        UploadDocumentCommand command = uploadCommandWithStrategy(
                "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":{\"chunk_token_num\":0}}}");

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.upload(command));

        // then：400 指明字段与值域；校验前置于对象存储与登记事务——零落文件、零登记，不产生半写状态
        assertTrue(exception.getMessage().contains("chunk_token_num"));
        assertTrue(exception.getMessage().contains("(0,2048]"));
        verify(kbAssetStoragePort, never()).putSource(anyString(), any(byte[].class), any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void should_registerDocumentWithChunkStrategy_when_upload_given_legalChunkStrategy() {
        // given：文档级策略合法（GENERAL + 值域内参数）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String chunkStrategy = "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":"
                + "{\"chunk_token_num\":1024,\"overlapped_percent\":15}}}";

        // when
        UploadDocumentResult result = service.upload(uploadCommandWithStrategy(chunkStrategy));

        // then：合法配置原样落库，文档登记为 PENDING
        assertEquals(chunkStrategy, result.document().chunkStrategy());
        assertEquals(DocumentStatus.PENDING, result.document().status());
        verify(documentRepository).save(any());
    }

    @Test
    void should_registerDocument_when_upload_given_legacyDeprecatedKeysInChunkStrategy() {
        // given：存量文档级策略含历史废弃键 enable_children
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String chunkStrategy = "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":"
                + "{\"chunk_token_num\":1024,\"enable_children\":true}}}";

        // when
        UploadDocumentResult result = service.upload(uploadCommandWithStrategy(chunkStrategy));

        // then：废弃键忽略不报错，编辑后的策略照常保存
        assertEquals(chunkStrategy, result.document().chunkStrategy());
        verify(documentRepository).save(any());
    }

    @Test
    void should_throwBadRequestWithoutDbInteraction_when_reparse_given_illegalChunkStrategyOverride() {
        // given：重解析覆盖值携带白名单外的模式名
        String override = "{\"chunkMode\":\"auto\"}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.reparse(new ReparseDocumentCommand(DOC_ID, override)));

        // then：400 列出七枚举合法值；校验前置于事务——不加锁、不写库、不进入 PENDING
        assertTrue(exception.getMessage().contains("GENERAL/QA/BOOK/LAWS/TABLE/PRESENTATION/ONE"));
        verifyNoInteractions(documentRepository);
        verifyNoInteractions(knowledgeBaseRepository);
    }

    @Test
    void should_throwBadRequestWithoutUpdate_when_reparse_given_illegalKnowledgeBaseChunkStrategy() {
        // given：未携带覆盖值，回退的库级快照里重叠比例 45 越界
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 5)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE,
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                        + "\"modeConfig\":{\"params\":{\"overlapped_percent\":45}}}}")));

        // when // then：继承结果同样拒绝，文档行保持原状态，不留「策略已刷新但状态已 PENDING」半写
        assertThrows(DeepDataAgentException.class, () -> service.reparse(new ReparseDocumentCommand(DOC_ID, null)));
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_refreshStrategyWithLegacyKeys_when_reparse_given_deprecatedKeysInOverride() {
        // given：覆盖值含历史废弃键（存量配置编辑后重新解析）
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 8, "{\"chunkMode\":\"SPLIT\"}")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, KB_ENGINE_CONFIG)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String override = "{\"chunkMode\":\"GENERAL\",\"modeConfig\":{\"params\":"
                + "{\"chunk_token_num\":1024,\"table_context_size\":3}}}";

        // when
        service.reparse(new ReparseDocumentCommand(DOC_ID, override));

        // then：忽略废弃键放行，策略按覆盖值刷新
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(captor.capture());
        assertEquals(override, captor.getValue().chunkStrategy());
        assertEquals(DocumentStatus.PENDING, captor.getValue().status());
    }

    @Test
    void should_useFileTypeFromCommand_when_upload_given_extensionStyleType() {
        // given
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.upload(uploadCommand(".docx"));

        // then：文件格式取命令声明值经服务端解析的枚举，对象键扩展名与其同源
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals(FileType.DOCX, captor.getValue().fileType());
        assertTrue(capturedStoredObjectKey().endsWith(".docx"));
    }

    @Test
    void should_throwConflictWithSummaryAndCompensate_when_upload_given_rejectActionWithDuplicate() {
        // given
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"REJECT\"}");
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.REJECT);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        DedupHit hit = new DedupHit(buildDocumentWithId(DOC_ID), List.of("fileName"));
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any())).thenReturn(List.of(hit));
        when(documentDedupService.describeHits(any()))
                .thenReturn("检测到重复文档：文档ID=100「手册.pdf」（命中轴：fileName）");

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.upload(uploadCommand("PDF")));
        assertTrue(ex.getMessage().contains("检测到重复文档"));
        verify(documentRepository, never()).save(any());

        // then：登记未发生，事务外补偿删除刚写入的对象（键与写入键一致）
        verifyStoredObjectRecycled();
    }

    @Test
    void should_returnExistingDocumentAndCompensate_when_upload_given_skipActionWithDuplicate() {
        // given
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"SKIP\"}");
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.SKIP);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        Document existing = buildDocumentWithId(DOC_ID);
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any()))
                .thenReturn(List.of(new DedupHit(existing, List.of("fileName"))));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then
        assertTrue(result.skipped());
        assertEquals(existing, result.document());
        verify(documentRepository, never()).save(any());
        verifyStoredObjectRecycled();
    }

    @Test
    void should_stillReturnSkipped_when_upload_given_compensateDeleteFails() {
        // given：SKIP 命中后的补偿删除失败（对象存储不可达），残留对象由既有清理语义兜底
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"SKIP\"}");
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.SKIP);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any())).thenReturn(
                List.of(new DedupHit(buildDocumentWithId(DOC_ID), List.of("fileName"))));
        doThrow(new RuntimeException("对象存储不可达")).when(kbAssetStoragePort).delete(anyString());

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：补偿删除失败仅 WARN 留痕，不改变对外响应
        assertTrue(result.skipped());
    }

    // ==================== 覆盖换版·受理段与事务外双投递 ====================

    /**
     * 装配覆盖处置的判重前置桩：知识库 OVERWRITE 策略 + 命中列表。
     *
     * @param hits 判重命中列表（文档ID升序，与仓储查询返回序天然一致）
     */
    private void stubOverwritePolicy(List<DedupHit> hits) {
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"OVERWRITE\"}");
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.OVERWRITE);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any())).thenReturn(hits);
    }

    /**
     * 装配单条命中旧文档的受理段桩：锁内读到指定快照 + CAS 推入 DELETING 是否命中。
     *
     * @param staleId 旧文档ID
     * @param stale   锁内读到的旧文档快照
     * @param casHit  CAS 是否命中（false 表示锁内行已处于 DELETING，走幂等受理分支）
     */
    private void stubStaleAcceptance(Long staleId, Document stale, boolean casHit) {
        when(documentRepository.findByIdForUpdate(staleId)).thenReturn(Optional.of(stale));
        when(documentRepository.transitStatus(eq(staleId), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(casHit);
    }

    /**
     * 构造本次换版新登记的 PENDING 文档快照（保存后回带主键，供双投递目标断言）。
     *
     * @return 新登记的文档聚合根
     */
    private Document savedPendingDocument() {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(NEW_DOC_ID, KB_ID, "手册.pdf", FileType.PDF, DocumentStatus.PENDING, null,
                1024L, 0, null, ImportType.UPLOAD, null, null, null, now, now);
    }

    @Test
    void should_transitStaleToDeletingAndSaveNewPending_when_upload_given_overwriteActionWithMultipleDuplicates() {
        // given：两条已处理命中（文档ID升序 90 / 100），受理段 CAS 均命中
        Document older = buildDocumentWithId(90L);
        Document newer = buildDocumentWithId(DOC_ID);
        stubOverwritePolicy(List.of(new DedupHit(older, List.of("fileName")),
                new DedupHit(newer, List.of("contentHash"))));
        stubStaleAcceptance(90L, older, true);
        stubStaleAcceptance(DOC_ID, newer, true);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：登记未跳过；旧文档全部以 DELETING 快照回传，新文档停留 PENDING
        assertFalse(result.skipped());
        assertEquals(List.of(90L, DOC_ID), result.overwritten().stream().map(Document::id).toList());
        assertTrue(result.overwritten().stream().allMatch(stale -> stale.status() == DocumentStatus.DELETING));
        assertEquals(DocumentStatus.PENDING, result.document().status());

        // then：同一受理事务内按文档ID升序固定锁序推进，且只推状态、不做任何物理清退
        InOrder inOrder = inOrder(documentRepository);
        inOrder.verify(documentRepository).transitStatus(90L, ACCEPT_SOURCE_STATUSES, DocumentStatus.DELETING, null);
        inOrder.verify(documentRepository).transitStatus(DOC_ID, ACCEPT_SOURCE_STATUSES, DocumentStatus.DELETING, null);
        inOrder.verify(documentRepository).save(any());
        verify(documentRepository, never()).executeDelete(any());
        verify(chunkRepository, never()).deleteByDocumentId(any());

        // then：新文档已引用本次写入的对象，MUST NOT 清理
        verifyStoredObjectKept();
    }

    @Test
    void should_submitBothCleanupAndIngestion_when_upload_given_overwriteCommitted() {
        // given：单条已处理命中，清退任务投递端口受理本次提交
        Document stale = buildDocumentWithId(DOC_ID);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, true);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());
        when(documentCleanupTaskSubmitter.submit(eq(DOC_ID), any(Runnable.class))).thenReturn(true);

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：受理事务提交后先投递旧文档清退任务，再为新文档投递解析任务
        assertFalse(result.skipped());
        InOrder inOrder = inOrder(documentCleanupTaskSubmitter, ingestionTaskSubmitter);
        inOrder.verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
        inOrder.verify(ingestionTaskSubmitter).submit(NEW_DOC_ID);
    }

    @Test
    void should_notSubmitCleanupOrIngestion_when_upload_given_newDocumentSaveFailing() {
        // given：受理段保存新文档抛异常 → 整段受理事务不提交，主流程走补偿
        Document stale = buildDocumentWithId(DOC_ID);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, true);
        when(documentRepository.save(any())).thenThrow(new RuntimeException("落库失败"));

        // when // then：异常原样上抛，双投递一段都不触达（未提交即不投递）
        assertThrows(RuntimeException.class, () -> service.upload(uploadCommand("PDF")));
        verify(documentCleanupTaskSubmitter, never()).submit(any(), any());
        verify(ingestionTaskSubmitter, never()).submit(any());

        // then：本次写入的源文件对象被补偿回收
        verifyStoredObjectRecycled();
    }

    @Test
    void should_continueIngestionSubmit_when_upload_given_cleanupSubmitThrowing() {
        // given：清退任务投递抛异常（如执行器已关闭），仅 ERROR 留痕
        Document stale = buildDocumentWithId(DOC_ID);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, true);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());
        when(documentCleanupTaskSubmitter.submit(eq(DOC_ID), any(Runnable.class)))
                .thenThrow(new RuntimeException("执行器已关闭"));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：受理不回滚、摄入投递照常发起——两侧投递各自独立，MUST NOT 相互阻断
        assertFalse(result.skipped());
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
        verify(ingestionTaskSubmitter).submit(NEW_DOC_ID);
    }

    @Test
    void should_writeFailedStatusQuietly_when_upload_given_ingestionSubmitThrowingAfterOverwrite() {
        // given：清退已正常投递，摄入投递被队列拒收（返回 false）
        Document stale = buildDocumentWithId(DOC_ID);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, true);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());
        when(ingestionTaskSubmitter.submit(NEW_DOC_ID)).thenReturn(false);

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：摄入投递失败不连带影响已完成的清退投递
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));

        // then：新文档以 PENDING→FAILED 条件流转闭合孤儿窗口，上传响应不回退
        assertFalse(result.skipped());
        verify(documentRepository).transitStatus(NEW_DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败");
    }

    @Test
    void should_acceptIdempotently_when_upload_given_staleAlreadyDeleting() {
        // given：并发换版下命中行已处于 DELETING（CAS 未命中），按幂等受理继续
        Document stale = buildDocument(DocumentStatus.DELETING, 0);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, false);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：旧文档仍以 DELETING 快照回传，新文档照常登记
        assertFalse(result.skipped());
        assertEquals(1, result.overwritten().size());
        assertEquals(DocumentStatus.DELETING, result.overwritten().get(0).status());
        verify(documentRepository).save(any());

        // then：清退任务照常投递，在飞去重由提交端口自身裁决
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
    }

    @Test
    void should_acceptStaleProcessingDocument_when_upload_given_staleInProcessing() {
        // given：命中旧文档正在解析中——覆盖换版不再像旧直删链那样 409 拒绝
        Document stale = buildDocument(DocumentStatus.PROCESSING, 0);
        stubOverwritePolicy(List.of(new DedupHit(stale, List.of("fileName"))));
        stubStaleAcceptance(DOC_ID, stale, true);
        when(documentRepository.save(any())).thenReturn(savedPendingDocument());

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：PROCESSING 属五源态集，CAS 推入 DELETING 后新文档照常登记并双投递
        assertFalse(result.skipped());
        verify(documentRepository).transitStatus(DOC_ID, ACCEPT_SOURCE_STATUSES, DocumentStatus.DELETING, null);
        verify(documentRepository).save(any());
        verify(documentCleanupTaskSubmitter).submit(eq(DOC_ID), any(Runnable.class));
        verify(ingestionTaskSubmitter).submit(NEW_DOC_ID);
    }

    @Test
    void should_register_when_upload_given_duplicateHitsButMissingAction() {
        // given
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE);
        stubUploadPremises(kb);
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, null);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        when(documentDedupService.detect(eq(KB_ID), eq(policy), any(), any()))
                .thenReturn(List.of(new DedupHit(buildDocumentWithId(DOC_ID), List.of("fileName"))));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：策略未给出处置动作时按登记放行，本次写入对象被新文档引用不清理
        assertFalse(result.skipped());
        verify(documentRepository).save(any());
        verifyStoredObjectKept();
    }

    @Test
    void should_throwBadRequest_when_precheck_given_bothAxesBlank() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.precheck(KB_ID, " ", null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_throwNotFound_when_precheck_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.precheck(KB_ID, "手册.pdf", null));
    }

    @Test
    void should_throwBadRequest_when_precheck_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.precheck(KB_ID, "手册.pdf", null));
        verify(documentDedupService, never()).detect(any(), any(), any(), any());
    }

    @Test
    void should_returnHits_when_precheck_given_duplicateDetected() {
        // given
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_NAME\",\"conflictAction\":\"REJECT\"}");
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_NAME, DedupConflictAction.REJECT);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        DedupHit hit = new DedupHit(buildDocumentWithId(DOC_ID), List.of("fileName"));
        when(documentDedupService.detect(eq(KB_ID), eq(policy), eq("手册.pdf"), isNull()))
                .thenReturn(List.of(hit));

        // when
        List<DedupHit> result = service.precheck(KB_ID, "手册.pdf", null);

        // then
        assertEquals(List.of(hit), result);
    }

    @Test
    void should_passLowercaseHashToDetect_when_precheck_given_uppercaseContentHash() {
        // given：大写十六进制为同一算法（SHA-256）的表示法差异，归一化为小写后再进入判重比对
        KnowledgeBase kb = buildKb(LifecycleStatus.ACTIVE, null,
                "{\"matchRule\":\"BY_CONTENT\",\"conflictAction\":\"REJECT\"}");
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        DedupPolicyConfig policy = new DedupPolicyConfig(DedupMatchRule.BY_CONTENT, DedupConflictAction.REJECT);
        when(documentDedupService.resolvePolicy(kb)).thenReturn(policy);
        String upperHash = SERVER_HASH.toUpperCase();
        when(documentDedupService.detect(eq(KB_ID), eq(policy), isNull(), eq(SERVER_HASH)))
                .thenReturn(List.of());

        // when
        List<DedupHit> result = service.precheck(KB_ID, null, upperHash);

        // then：服务端按归一化后的小写哈希查询
        assertTrue(result.isEmpty());
        verify(documentDedupService).detect(KB_ID, policy, null, SERVER_HASH);
    }

    @Test
    void should_throwBadRequestWithoutDetect_when_precheck_given_illegalContentHash() {
        // given：32 位 MD5 摘要属不同算法，一律拒绝且不得静默放行
        String md5Hash = "900150983cd24fb0d6963f7d28e17f72";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.precheck(KB_ID, null, md5Hash));
        verify(documentDedupService, never()).detect(any(), any(), any(), any());
    }

    /** 测试文档源文件对象键（rag 命名空间，定点回收的删除目标） */
    private static final String SOURCE_OBJECT_KEY = "rag/10/source/manual.pdf";

    /**
     * 构造携带文件存储引用的文档（openContent 场景专用）。
     *
     * @param s3File 文件存储引用 JSON，可为 null
     * @return 已处理状态的文档实例
     */
    private Document buildDocumentWithS3File(String s3File) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, KB_ID, "手册.pdf", FileType.PDF, DocumentStatus.PROCESSED, null,
                1024L, 3, null, ImportType.UPLOAD, s3File, null, null, now, now);
    }

    /**
     * 构造指定文件类型的文档（openContent 类型映射断言专用）。
     *
     * @param fileType 文件类型
     * @param s3File   文件存储引用 JSON
     * @return 已处理状态的文档实例
     */
    private Document buildDocumentWithS3FileAndType(FileType fileType, String fileName, String s3File) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, KB_ID, fileName, fileType, DocumentStatus.PROCESSED, null,
                1024L, 3, null, ImportType.UPLOAD, s3File, null, null, now, now);
    }

    @Test
    void should_returnContentStream_when_openContent_given_validDocument() throws Exception {
        // given
        String s3File = "{\"objectKey\":\"rag/10/source/手册.pdf\"}";
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File(s3File)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(kbAssetStoragePort.open("rag/10/source/手册.pdf")).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L)));

        // when
        DocumentContentResult result = service.openContent(DOC_ID);

        // then：contentType 按文档登记的 FileType 映射（不再读取存储元数据），字节数取 open 精确值
        assertEquals("手册.pdf", result.fileName());
        assertEquals("application/pdf", result.contentType());
        assertEquals(3L, result.contentLength());
        try (InputStream content = result.content()) {
            assertArrayEquals(new byte[] {1, 2, 3}, content.readAllBytes());
        }
        verify(kbAssetStoragePort).open("rag/10/source/手册.pdf");
    }

    @Test
    void should_mapContentTypeByRegisteredFileType_when_openContent_given_markdownDocument() {
        // given：MD 文档按类型映射为 text/markdown（旧「元数据 contentType 优先 / 空白回落」已随迁移消失）
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(
                buildDocumentWithS3FileAndType(FileType.MD, "手册.md", "{\"objectKey\":\"rag/10/source/手册.md\"}")));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(kbAssetStoragePort.open("rag/10/source/手册.md")).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(new byte[] {1}), 5L)));

        // when
        DocumentContentResult result = service.openContent(DOC_ID);

        // then
        assertEquals("text/markdown", result.contentType());
        assertEquals(5L, result.contentLength());
    }

    @Test
    void should_throwNotFound_when_openContent_given_documentNotExist() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.openContent(DOC_ID));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    @Test
    void should_throwNotFound_when_openContent_given_sourceObjectMissing() {
        // given：存储引用合法但对象存储中对象不存在（open 返回 empty）
        String s3File = "{\"objectKey\":\"rag/10/source/手册.pdf\"}";
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File(s3File)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(kbAssetStoragePort.open("rag/10/source/手册.pdf")).thenReturn(Optional.empty());

        // when
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("文档源文件对象不存在"));
    }

    @Test
    void should_throwBadRequest_when_openContent_given_knowledgeBaseNotExist() {
        // given
        String s3File = "{\"objectKey\":\"rag/10/source/手册.pdf\"}";
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File(s3File)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("知识库不可用"));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    @Test
    void should_throwBadRequest_when_openContent_given_deletingKnowledgeBase() {
        // given
        String s3File = "{\"objectKey\":\"rag/10/source/手册.pdf\"}";
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File(s3File)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("知识库不可用"));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    @Test
    void should_throwBadRequest_when_openContent_given_blankS3File() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File(null)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("未登记文件存储引用"));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    @Test
    void should_throwBadRequest_when_openContent_given_malformedS3FileJson() {
        // given
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithS3File("{\"objectKey\": \"rag")));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("不是合法的JSON"));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    @Test
    void should_throwBadRequest_when_openContent_given_missingObjectKey() {
        // given：引用 JSON 缺对象键（桶键已随桶概念退役，仅对象键定位）
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithS3File("{\"bucket\":\"kb-bucket\"}")));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.openContent(DOC_ID));

        // then
        assertTrue(exception.getMessage().contains("缺少对象键"));
        verify(kbAssetStoragePort, never()).open(anyString());
    }

    // ==================== 删除链·媒体图片对象清理 ====================

    /** 携带源文件对象引用的文档（删除链资产清理用例专用，状态为 DELETING，引用仅对象键） */
    private Document buildDocumentWithMedia() {
        OffsetDateTime now = OffsetDateTime.now();
        String s3File = "{\"objectKey\":\"" + SOURCE_OBJECT_KEY + "\"}";
        return Document.restore(DOC_ID, KB_ID, "手册.pdf", FileType.PDF, DocumentStatus.DELETING, null,
                1024L, 3, null, ImportType.UPLOAD, s3File, null, null, now, now);
    }

    @Test
    void should_cleanupByDocumentPrefixOnceAndClose_when_runCleanupChain_given_documentWithImages() {
        // given：无残留切片（清退聚焦资产回收步骤）；新契约下媒体图片按文档前缀单次清退，
        // 不逐对象列举（对象不存在=成功，天然幂等）
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocumentWithMedia()));
        String prefix = "rag/" + KB_ID + "/" + DOC_ID + "/images/";
        when(chunkRepository.findIdsByDocumentId(DOC_ID, 500)).thenReturn(List.of());
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then：按正确文档前缀单次清退 + 源文件定点删除 1 次，正常收口、无失败留痕
        verify(kbAssetStoragePort).cleanupPrefix(prefix);
        verify(kbAssetStoragePort).delete(SOURCE_OBJECT_KEY);
        verify(documentRepository, never()).markFailed(any(), any());
        verify(documentRepository).executeDelete(DOC_ID);
    }

    @Test
    void should_cleanupMediaPrefixOnlyAndCloseQuietly_when_runCleanupChain_given_documentWithoutSourceReference() {
        // given：文档无有效源文件引用（源文件定点回收跳过），媒体前缀仅由 ID 派生仍可定位
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DocumentStatus.DELETING, 3)));
        when(chunkRepository.findIdsByDocumentId(DOC_ID, 500)).thenReturn(List.of());

        // when
        service.runCleanupChain(DOC_ID);

        // then：媒体前缀单次清退照常执行，源文件定点删除零发起，链照常收口
        verify(kbAssetStoragePort).cleanupPrefix("rag/" + KB_ID + "/" + DOC_ID + "/images/");
        verify(kbAssetStoragePort, never()).delete(anyString());
        verify(documentRepository).executeDelete(DOC_ID);
        verify(documentRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_continueChainAndClose_when_runCleanupChain_given_awaitTerminationTimeout() {
        // given：掐灭等待超时返回 false → WARN 留痕后继续清退并收口（worker 必经 DELETING 闸门自灭）
        stubChainPremisesWithoutMedia(List.of());
        when(ingestionCancellationApi.awaitTermination(DOC_ID, Duration.ofSeconds(30))).thenReturn(false);
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then：等待时长按配置秒数换算，超时不算失败、不留痕，照常收口
        verify(ingestionCancellationApi).awaitTermination(DOC_ID, Duration.ofSeconds(30));
        verify(documentRepository, never()).markFailed(any(), any());
        verify(documentRepository).executeDelete(DOC_ID);
    }

    @Test
    void should_notDeleteAnyAsset_when_reparse_given_processedDocument() {
        // given：重新解析正常成功（重解析绝不允许回收源文件——源文件是重解析的生命线）
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 8)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.reparse(new ReparseDocumentCommand(DOC_ID, null));

        // then：重解析路径零资产删除——不触碰对象存储、不发起切片物理清退（回归锁死）
        verifyNoInteractions(kbAssetStoragePort);
        verifyNoInteractions(chunkPhysicalDeleteService);
    }

    // ==================== 在飞任务登记与状态一致性收敛 ====================

    @Test
    void should_registerBeforeSubmit_when_submitIngestionTask_given_normalUpload() {
        // given：登记成功返回带 ID 的 PENDING 文档，投递契约默认入队成功（setUp 桩）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));

        // when
        service.upload(uploadCommand("PDF"));

        // then：先在飞登记、后投递解析任务——登记先于投递，投递失败也留有残留凭据
        InOrder inOrder = inOrder(inFlightTaskRegistry, ingestionTaskSubmitter);
        inOrder.verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
        inOrder.verify(ingestionTaskSubmitter).submit(DOC_ID);
    }

    @Test
    void should_revertToFailedWithoutSubmit_when_submitIngestionTask_given_registerThrows() {
        // given：在飞注册表不可用（登记抛异常）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        doThrow(new RuntimeException("在飞注册表不可用"))
                .when(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败")).thenReturn(true);

        // when
        UploadDocumentResult result = service.upload(uploadCommand("PDF"));

        // then：登记失败 MUST NOT 静默放行——不入队、直接回写 FAILED 闭合孤儿窗口
        assertFalse(result.skipped());
        verify(ingestionTaskSubmitter, never()).submit(any());
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败");
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    @Test
    void should_markDeleteFailedWithoutSubmit_when_submitDocumentCleanupAfterCommit_given_registerThrows() {
        // given：受理 CAS 命中，但清退任务的在飞登记抛异常
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 3)));
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(true);
        doThrow(new RuntimeException("在飞注册表不可用"))
                .when(inFlightTaskRegistry).register(InFlightTaskType.DOC_CLEANUP, DOC_ID);

        // when
        Document accepted = service.delete(new DeleteDocumentCommand(DOC_ID));

        // then：受理不回滚、不投递清退任务，按失败兜底留痕 step=in_flight_register
        assertEquals(DocumentStatus.DELETING, accepted.status());
        verify(documentCleanupTaskSubmitter, never()).submit(any(), any());
        verify(documentRepository).markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=in_flight_register"));
    }

    /**
     * 场景：登记调用点位于数据库事务提交之后。
     * 预期：事务体外才登记——事务提交（{@code transactionTemplate.execute} 返回）先于在飞登记。
     */
    @Test
    void should_registerAfterTransactionCommit_when_submitIngestionTask_given_normalUpload() {
        // given：登记成功返回带 ID 的 PENDING 文档，投递契约默认入队成功（setUp 桩）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));

        // when
        service.upload(uploadCommand("PDF"));

        // then：在飞登记（事务外动作）严格晚于数据库事务提交，事务体内不得出现登记调用
        InOrder inOrder = inOrder(transactionTemplate, inFlightTaskRegistry);
        inOrder.verify(transactionTemplate).execute(any());
        inOrder.verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
    }

    /**
     * 场景：文档已落库为 PENDING 但投递至执行队列失败（端口返回 false）。
     * 预期：登记先于投递，投递失败仍留有残留凭据；FAILED 回写成功后才移除在飞成员。
     */
    @Test
    void should_keepInFlightCredentialUntilFailedWriteBack_when_submitIngestionTask_given_submitRejected() {
        // given：投递被拒收，FAILED 回写成功（状态已收敛）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        when(ingestionTaskSubmitter.submit(DOC_ID)).thenReturn(false);
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败")).thenReturn(true);

        // when
        service.upload(uploadCommand("PDF"));

        // then：残留凭据已留（可被本实例启动恢复收敛），且次序为
        // 登记 → 投递 → FAILED 回写 → 移除成员
        InOrder inOrder = inOrder(inFlightTaskRegistry, ingestionTaskSubmitter, documentRepository);
        inOrder.verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
        inOrder.verify(ingestionTaskSubmitter).submit(DOC_ID);
        inOrder.verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败");
        inOrder.verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOC_ID);
    }

    /**
     * 场景：投递失败且 FAILED 回写未命中（数据行已不存在或状态已被并发推进）。
     * 预期：未命中同属「状态已收敛」——该文档已不再是本实例的在飞解析任务，成员被移除，
     * MUST NOT 滞留到下次重启才清理。
     */
    @Test
    void should_unregisterIngestionMember_when_submitIngestionTask_given_failedWriteBackNotHit() {
        // given：投递被拒收，回写 FAILED 未命中（返回 false）
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        when(ingestionTaskSubmitter.submit(DOC_ID)).thenReturn(false);
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败")).thenReturn(false);

        // when
        service.upload(uploadCommand("PDF"));

        // then：登记已发生（凭据曾留），且回写未命中即判已收敛 → 成员被移除
        verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, DOC_ID);
    }

    /**
     * 场景：投递失败且 FAILED 回写抛异常（状态是否收敛未知）。
     * 预期：在飞成员保留，交由本实例启动恢复按状态一致性校验兜底，MUST NOT 移除。
     */
    @Test
    void should_keepInFlightCredential_when_submitIngestionTask_given_failedWriteBackThrows() {
        // given：投递被拒收，回写 FAILED 抛数据库异常
        stubUploadPremises(buildKb(LifecycleStatus.ACTIVE));
        when(documentRepository.save(any())).thenReturn(buildDocument(DocumentStatus.PENDING, 0));
        when(ingestionTaskSubmitter.submit(DOC_ID)).thenReturn(false);
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.FAILED, "解析任务提交失败")).thenThrow(new IllegalStateException("DB 不可用"));

        // when
        service.upload(uploadCommand("PDF"));

        // then：状态未确认收敛 → 成员保留，MUST NOT 移除
        verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_INGESTION, DOC_ID);
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 场景：文档清退任务受理成功但执行器提交失败（停机拒收抛异常）。
     * 预期：文档停留 DELETING，其在飞凭据仍在，供本实例启动恢复收敛为 DELETE_FAILED。
     */
    @Test
    void should_keepCleanupCredential_when_submitDocumentCleanup_given_submitThrows() {
        // given：受理 CAS 命中，清退任务投递抛停机异常
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(DocumentStatus.PROCESSED, 3)));
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.DELETING), isNull()))
                .thenReturn(true);
        when(documentCleanupTaskSubmitter.submit(eq(DOC_ID), any(Runnable.class)))
                .thenThrow(new IllegalStateException("删除清退执行器已停机"));

        // when
        service.delete(new DeleteDocumentCommand(DOC_ID));

        // then：登记先于投递，投递失败亦留有残留凭据且成员不被移除
        verify(inFlightTaskRegistry).register(InFlightTaskType.DOC_CLEANUP, DOC_ID);
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
        verify(documentRepository, never()).markFailed(any(), any());
    }

    /**
     * 场景：清退任务异常，且 DELETE_FAILED 留痕写入自身异常。
     * 预期：状态收敛失败必须保留在飞成员，交由下次启动重试。
     */
    @Test
    void should_keepCleanupMember_when_runCleanupChain_given_markFailedThrows() {
        // given：清退链首步（掐灭等待）异常，且失败留痕写入自身异常（状态未知）
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class)))
                .thenThrow(new RuntimeException("等待被中断"));
        when(documentRepository.markFailed(eq(DOC_ID), anyString()))
                .thenThrow(new RuntimeException("留痕写入失败"));

        // when：任务体自带全量 catch，异常不逃逸
        service.runCleanupChain(DOC_ID);

        // then：MUST NOT 移除成员（否则产生状态停在中间态而注册表无迹可寻的悬挂）
        verify(documentRepository).markFailed(eq(DOC_ID), anyString());
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 场景：清退任务异常，DELETE_FAILED 留痕写入成功。
     * 预期：置态成功（状态已收敛）后才移除在飞成员。
     */
    @Test
    void should_unregisterCleanupMember_when_runCleanupChain_given_markFailedHit() {
        // given：掐灭等待抛异常，失败留痕命中并置 DELETE_FAILED
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class)))
                .thenThrow(new RuntimeException("等待被中断"));
        when(documentRepository.markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=cancel_wait")))
                .thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then：置态成功即移除成员
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_CLEANUP, DOC_ID);
    }

    /**
     * 场景：清退任务异常，失败留痕未命中（行已收口或状态已被并发推进）。
     * 预期：未命中即状态已收敛，成员同样移除且不再等待下次启动。
     */
    @Test
    void should_unregisterCleanupMember_when_runCleanupChain_given_markFailedNotHit() {
        // given：掐灭等待抛异常，失败留痕未命中（markFailed 返回 false）
        when(ingestionCancellationApi.awaitTermination(eq(DOC_ID), any(Duration.class)))
                .thenThrow(new RuntimeException("等待被中断"));
        when(documentRepository.markFailed(eq(DOC_ID), startsWith("[DELETE-FAILED] step=cancel_wait")))
                .thenReturn(false);

        // when
        service.runCleanupChain(DOC_ID);

        // then：已收敛分支同样移除成员
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_CLEANUP, DOC_ID);
    }

    /**
     * 场景：清退链正常收口（条件物理 DELETE 命中行）。
     * 预期：收口成功（行已物理删除）＝状态已收敛，移除在飞成员且无失败留痕。
     */
    @Test
    void should_unregisterCleanupMember_when_runCleanupChain_given_closeSucceeded() {
        // given：无媒体资产文档、无残留切片，收口条件 DELETE 命中
        stubChainPremisesWithoutMedia(List.of());
        when(documentRepository.executeDelete(DOC_ID)).thenReturn(true);

        // when
        service.runCleanupChain(DOC_ID);

        // then
        verify(documentRepository, never()).markFailed(any(), any());
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_CLEANUP, DOC_ID);
    }
}

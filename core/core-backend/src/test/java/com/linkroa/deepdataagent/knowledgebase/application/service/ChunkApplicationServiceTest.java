package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkEmbeddingApi;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.knowledgebase.application.command.CreateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunksCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListChunkQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.ChunkMediaResult;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkApplicationService} 单元测试。
 * <p>新增 / 编辑用例按「事务外取向量、事务内写切片行 + UPSERT 1:1 派生表示」口径覆盖：
 * 向量字面量与切片原文字面落表、未配置嵌入模型的降级（仅写全文）、向量计算失败零写入、
 * 向量化先于事务开启的时序，以及切片行缺失 404 零依赖触达。</p>
 * <p>删除用例按「Storage 先删、DB 后删」口径覆盖：
 * 闸门（切片/文档/知识库缺失 404、知识库删除中拒绝、文档删除链 409）、
 * 来源准入（本批必须全为「人工新增」，含解析块整批拒绝并附引导文案）、
 * 图片对象独占先回收后走事务、共享引用跳过回收、回收失败数据库零写入、
 * 对象不存在视为成功继续、删除原语 3 参口径（图谱收敛由批次数据推导，不在调用方声明面），
 * 以及批量删除命令路径（去重、分批、批失败终止）。</p>
 * <p>口径说明：桶概念已退役，chunk.s3_file 与媒体引用 meta 均仅对象键单分量；
 * 图片对象回收 {@code recycleChunkMediaObject} 为 void（幂等删除，失败抛业务异常）；
 * 媒体预览 {@code openMedia} 经 {@link KbAssetStoragePort#open(String)} 只读代理，
 * 对象缺失回传 404，contentType 按对象键扩展名推断。</p>
 * <p>新增用例按「序号服务端分配」口径覆盖：人工新增恒为该文档当前最大序号加一
 * （客户端传入序号被忽略）、无切片时自 0 起分配、恒打「人工新增」来源标。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChunkApplicationServiceTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 测试用文档ID */
    private static final Long DOC_ID = 100L;

    /** 测试用切片ID */
    private static final Long CHUNK_ID = 1000L;

    /** 非本知识库的知识库ID，用于校验文档归属关系 */
    private static final Long OTHER_KB_ID = 99L;

    /** 多模态图片对象引用 JSON（chunk.s3_file 一等列形态，仅对象键，桶概念已退役） */
    private static final String MEDIA_REFERENCE_JSON =
            "{\"objectKey\":\"rag/10/100/images/a.png\"}";

    /** 与 {@link #MEDIA_REFERENCE_JSON} 对应的图片对象引用值对象 */
    private static final S3File MEDIA_REFERENCE =
            new S3File("rag/10/100/images/a.png");

    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private ChunkRepresentationRepository chunkRepresentationRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ChunkPhysicalDeleteService chunkPhysicalDeleteService;
    @Mock
    private MediaImageCleanupService mediaImageCleanupService;
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;
    @Mock
    private ChunkEmbeddingApi chunkEmbeddingApi;

    @InjectMocks
    private ChunkApplicationService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private KnowledgeBase buildKb(LifecycleStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", status,
                null, null, null, null, null, null, null, now, now);
    }

    private Document buildDocument(Long kbId, int chunkCount) {
        return buildDocument(kbId, chunkCount, null);
    }

    /**
     * 构建处于 PROCESSING 的文档（摄入回写的合法前置态：队列出队领取后由消费线程持有处理权）。
     */
    private Document buildDocument(Long kbId, int chunkCount, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, kbId, "手册.pdf", FileType.PDF, DocumentStatus.PROCESSING, errorMessage,
                1024L, chunkCount, null, ImportType.UPLOAD, null, null, null, now, now);
    }

    private Document buildDocumentWithStatus(Long kbId, int chunkCount, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(DOC_ID, kbId, "手册.pdf", FileType.PDF, status, null,
                1024L, chunkCount, null, ImportType.UPLOAD, null, null, null, now, now);
    }

    private Chunk buildChunk(String content, Integer tokens) {
        OffsetDateTime now = OffsetDateTime.now();
        return Chunk.restore(CHUNK_ID, KB_ID, DOC_ID, 1, tokens, content,
                null, ChunkContentType.TEXT, "手册.pdf", null, ChunkSource.PARSED, now, now);
    }

    /**
     * 为新建切片补齐自增主键，模拟持久化层落库后的主键回填
     * （派生表示的组装依赖切片主键，切片主键缺失时无法组装表示）。
     */
    private Chunk withId(Chunk chunk) {
        return Chunk.restore(CHUNK_ID, chunk.kbId(), chunk.documentId(), chunk.sequence(), chunk.tokens(),
                chunk.chunkContent(), chunk.originalItem(), chunk.chunkContentType(), chunk.sourceFileName(),
                chunk.s3File(), chunk.sourceType(), chunk.createdAt(), chunk.updatedAt());
    }

    private CreateChunkCommand createCommand(String contentType) {
        return new CreateChunkCommand(KB_ID, DOC_ID, 1, 30, "切片内容",
                "{\"page\":1}", contentType, "手册.pdf");
    }

    /**
     * 装配人工新增事务内前置桩：事务体先取文档行锁（与整篇替换同一把锁），
     * 未持有行锁即按「文档不存在」中断——所有进入事务的新增用例必须装配。
     */
    private void stubCreateTransactionPremises() {
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
    }

    @Test
    void should_saveChunkAndRefreshCount_when_create_given_validCommand() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        stubCreateTransactionPremises();
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(5L);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk saved = service.create(createCommand("TABLE"));

        // then
        ArgumentCaptor<Chunk> chunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).save(chunkCaptor.capture());
        assertEquals(KB_ID, chunkCaptor.getValue().kbId());
        assertEquals(DOC_ID, chunkCaptor.getValue().documentId());
        assertEquals(ChunkContentType.TABLE, chunkCaptor.getValue().chunkContentType());
        // 人工新增入口恒打「人工新增」来源标
        assertEquals(ChunkSource.MANUAL, chunkCaptor.getValue().sourceType());
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(documentCaptor.capture());
        assertEquals(5, documentCaptor.getValue().chunkCount().intValue());
        assertEquals("切片内容", saved.chunkContent());
        assertEquals(30, saved.tokens().intValue());
    }

    /**
     * 序号服务端分配：新切片序号 = 该文档当前最大序号加一（人工块恒位于分块序列末尾），
     * 且序号计算发生在事务内文档行锁之后（并发新增被行锁串行化，杜绝序号竞态）。
     */
    @Test
    void should_assignMaxSequencePlusOne_when_create_given_existingChunks() {
        // given：文档当前最大序号为 7
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 8)));
        stubCreateTransactionPremises();
        when(chunkRepository.findMaxSequenceByDocumentId(DOC_ID)).thenReturn(7);
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.create(createCommand("TEXT"));

        // then：落库切片序号为 8；行锁先于序号计算
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).save(captor.capture());
        assertEquals(8, captor.getValue().sequence().intValue());
        InOrder order = inOrder(documentRepository, chunkRepository);
        order.verify(documentRepository).findByIdForUpdate(DOC_ID);
        order.verify(chunkRepository).findMaxSequenceByDocumentId(DOC_ID);
    }

    /**
     * 客户端传入序号一律被忽略：请求体携带的 sequence 不参与落库（恒为服务端计算值）。
     */
    @Test
    void should_ignoreClientSequence_when_create_given_clientProvidedSequence() {
        // given：命令携带非法的大序号 999，服务端计算值为 8
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 8)));
        stubCreateTransactionPremises();
        when(chunkRepository.findMaxSequenceByDocumentId(DOC_ID)).thenReturn(7);
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk saved = service.create(new CreateChunkCommand(KB_ID, DOC_ID, 999, 30, "切片内容",
                null, "TEXT", "手册.pdf"));

        // then：客户端序号 999 被忽略，落库序号仍为服务端计算值 8
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).save(captor.capture());
        assertEquals(8, captor.getValue().sequence().intValue());
        assertEquals(8, saved.sequence().intValue());
    }

    /**
     * 序号边界：文档尚无切片（最大序号查询返回 null）时首块自 0 起分配。
     */
    @Test
    void should_startSequenceFromZero_when_create_given_noExistingChunks() {
        // given：最大序号查询返回 null（文档无切片）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        stubCreateTransactionPremises();
        when(chunkRepository.findMaxSequenceByDocumentId(DOC_ID)).thenReturn(null);
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.create(createCommand("TEXT"));

        // then：首块序号为 0
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).save(captor.capture());
        assertEquals(0, captor.getValue().sequence().intValue());
    }

    /**
     * 事务内文档行锁读取缺失（持锁窗口内被并发终删）：按「文档不存在」中断，零落库。
     */
    @Test
    void should_throwNotFound_when_create_given_documentRowGoneInsideTransaction() {
        // given：事务外归属校验通过，事务内行锁读取已无行
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(createCommand("TEXT")));
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void should_defaultTextContentType_when_create_given_blankContentType() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        stubCreateTransactionPremises();
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(1L);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.create(createCommand("  "));

        // then
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).save(captor.capture());
        assertEquals(ChunkContentType.TEXT, captor.getValue().chunkContentType());
    }

    @Test
    void should_skipRefresh_when_create_given_documentRemovedBeforeRefresh() {
        // given：首次查询用于归属校验，回写时文档已被并发清理
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocument(KB_ID, 0)), Optional.empty());
        stubCreateTransactionPremises();
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        // when
        Chunk saved = service.create(createCommand("TEXT"));

        // then
        assertEquals(DOC_ID, saved.documentId());
        verify(chunkRepository, never()).countByDocumentId(any());
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_throwNotFound_when_create_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(createCommand("TEXT")));
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_create_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.create(createCommand("TEXT")));
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_create_given_documentNotExist() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(createCommand("TEXT")));
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_create_given_documentBelongsToOtherKb() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(OTHER_KB_ID, 0)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.create(createCommand("TEXT")));
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_create_given_unsupportedContentType() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.create(createCommand("AUDIO")));
        verify(chunkRepository, never()).save(any());
    }

    /**
     * 新增切片的正常路径：事务外取向量字面量 → 事务内写切片行 + UPSERT 1:1 派生表示
     * （向量等于模型返回字面量、全文等于切片正文），且无任何删除 / 投递副作用。
     */
    @Test
    void should_upsertRepresentationWithVector_when_create_given_embeddingAvailable() {
        // given：嵌入模型可用（返回 pgvector 字面量）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        stubCreateTransactionPremises();
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "切片内容")).thenReturn("[0.1,0.2]");
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(1L);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk saved = service.create(createCommand("TEXT"));

        // then：派生表示携带向量与切片正文，身份三元组取自落库切片
        ArgumentCaptor<ChunkRepresentation> captor = ArgumentCaptor.forClass(ChunkRepresentation.class);
        verify(chunkRepresentationRepository).upsert(captor.capture(), isNull());
        assertEquals(KB_ID, captor.getValue().kbId());
        assertEquals(DOC_ID, captor.getValue().documentId());
        assertEquals(CHUNK_ID, captor.getValue().chunkId());
        assertEquals("[0.1,0.2]", captor.getValue().embeddingVector());
        assertEquals("切片内容", captor.getValue().chunkContent());
        assertEquals(CHUNK_ID, saved.id());
        // 新增路径零删除 / 零投递副作用（待重建信号与重建投递已随聚合根一并移除）
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    /**
     * 未配置嵌入模型的降级口径：契约以「无向量」返回空，新增仍成功、照写全文表示。
     */
    @Test
    void should_writeFullTextRepresentationOnly_when_create_given_noEmbeddingModel() {
        // given：知识库未配置嵌入模型（返回 null，不抛异常）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        stubCreateTransactionPremises();
        when(chunkRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "切片内容")).thenReturn(null);
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(1L);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk saved = service.create(createCommand("TEXT"));

        // then：表示仅含全文（向量为空），切片照常落库
        ArgumentCaptor<ChunkRepresentation> captor = ArgumentCaptor.forClass(ChunkRepresentation.class);
        verify(chunkRepresentationRepository).upsert(captor.capture(), isNull());
        assertNull(captor.getValue().embeddingVector());
        assertEquals("切片内容", captor.getValue().chunkContent());
        assertEquals("切片内容", saved.chunkContent());
    }

    /**
     * 向量计算失败（上游不可达）：异常上抛，事务未开启，切片行 / 表示行 / 分块计数零写入。
     */
    @Test
    void should_throwAndWriteNothing_when_create_given_embeddingFails() {
        // given：向量化上游不可达
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "切片内容"))
                .thenThrow(new IllegalStateException("向量化上游不可达"));

        // when // then
        assertThrows(IllegalStateException.class, () -> service.create(createCommand("TEXT")));
        verify(transactionTemplate, never()).execute(any());
        verify(chunkRepository, never()).save(any());
        verify(chunkRepresentationRepository, never()).upsert(any(), any());
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_keepOriginTokens_when_update_given_blankTokens() {
        // given
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));
        when(chunkRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk updated = service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", null));

        // then
        assertEquals("新内容", updated.chunkContent());
        assertEquals(30, updated.tokens().intValue());
        assertEquals(CHUNK_ID, updated.id());
    }

    @Test
    void should_overrideTokens_when_update_given_newTokens() {
        // given
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));
        when(chunkRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Chunk updated = service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", 88));

        // then
        assertEquals(88, updated.tokens().intValue());
    }

    /**
     * 编辑切片的正常路径：事务外先取向量再进事务，并断言向量化调用发生在事务开启之前
     * （远程调用不得出现在数据库事务体内）。
     */
    @Test
    void should_upsertRepresentationWithVector_when_update_given_embeddingAvailable() {
        // given：嵌入模型可用，仓储返回携带库 / 文档身份的既有切片
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));
        when(chunkRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "新内容")).thenReturn("[0.5,0.5]");

        // when
        Chunk updated = service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", 30));

        // then：派生表示携带新正文与新向量，身份三元组取自被编辑切片
        ArgumentCaptor<ChunkRepresentation> captor = ArgumentCaptor.forClass(ChunkRepresentation.class);
        verify(chunkRepresentationRepository).upsert(captor.capture(), isNull());
        assertEquals(KB_ID, captor.getValue().kbId());
        assertEquals(DOC_ID, captor.getValue().documentId());
        assertEquals(CHUNK_ID, captor.getValue().chunkId());
        assertEquals("[0.5,0.5]", captor.getValue().embeddingVector());
        assertEquals("新内容", captor.getValue().chunkContent());
        assertEquals("新内容", updated.chunkContent());
        // 时序口径：向量化先于事务开启，事务内只做 DB 写入
        InOrder order = inOrder(chunkEmbeddingApi, transactionTemplate);
        order.verify(chunkEmbeddingApi).embedLiteral(KB_ID, "新内容");
        order.verify(transactionTemplate).execute(any());
        // 编辑任意来源分块均不动图谱（四表）：删除原语与媒体回收零触达——
        // 图谱账本收敛契约仅挂在删除原语内部，编辑路径不途经该原语
        verifyNoInteractions(chunkPhysicalDeleteService, mediaImageCleanupService);
    }

    /**
     * 未配置嵌入模型的降级口径：编辑仍成功、查看表示仅含全文（向量为空）。
     */
    @Test
    void should_writeFullTextRepresentationOnly_when_update_given_noEmbeddingModel() {
        // given：知识库未配置嵌入模型（返回 null，不抛异常）
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));
        when(chunkRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "新内容")).thenReturn(null);

        // when
        Chunk updated = service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", 30));

        // then：表示仅含全文（向量为空），切片正文照常更新
        ArgumentCaptor<ChunkRepresentation> captor = ArgumentCaptor.forClass(ChunkRepresentation.class);
        verify(chunkRepresentationRepository).upsert(captor.capture(), isNull());
        assertNull(captor.getValue().embeddingVector());
        assertEquals("新内容", captor.getValue().chunkContent());
        assertEquals("新内容", updated.chunkContent());
    }

    /**
     * 编辑时向量计算失败：异常上抛，事务未开启，切片行与表示行零写入（原内容原样保留）。
     */
    @Test
    void should_throwAndWriteNothing_when_update_given_embeddingFails() {
        // given：向量化上游不可达
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));
        when(chunkEmbeddingApi.embedLiteral(KB_ID, "新内容"))
                .thenThrow(new IllegalStateException("向量化上游不可达"));

        // when // then
        assertThrows(IllegalStateException.class,
                () -> service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", 30)));
        verify(transactionTemplate, never()).execute(any());
        verify(chunkRepository, never()).update(any());
        verify(chunkRepresentationRepository, never()).upsert(any(), any());
    }

    @Test
    void should_throwBadRequest_when_update_given_blankContent() {
        // given
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildChunk("旧内容", 30)));

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> service.update(new UpdateChunkCommand(CHUNK_ID, "   ", 10)));
        verify(chunkRepository, never()).update(any());
    }

    /**
     * 更新时切片行缺失：事务外预读即 404，零依赖触达（不发起向量计算、不进事务、零写入）。
     */
    @Test
    void should_throwNotFound_when_update_given_missingChunk() {
        // given
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(new UpdateChunkCommand(CHUNK_ID, "新内容", 10)));
        verifyNoInteractions(chunkEmbeddingApi);
        verify(transactionTemplate, never()).execute(any());
        verify(chunkRepository, never()).update(any());
        verify(chunkRepresentationRepository, never()).upsert(any(), any());
    }

    /**
     * 单条删除闸门全通过的默认桩：切片行在、文档未进删除链、知识库 ACTIVE，
     * 且来源准入通过（本批全为「人工新增」——人工删除入口的常态批次）。
     */
    private void stubDeleteGatePassThrough() {
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        stubManualSource(List.of(CHUNK_ID));
    }

    /**
     * 装配来源准入通过桩：指定批次在「切片 → 来源」投影中全部为「人工新增」。
     *
     * @param chunkIds 本批切片ID集合（与实现侧同构的等值列表）
     */
    private void stubManualSource(List<Long> chunkIds) {
        Map<Long, ChunkSource> manualOnly = new LinkedHashMap<>();
        chunkIds.forEach(chunkId -> manualOnly.put(chunkId, ChunkSource.MANUAL));
        when(chunkRepository.findSourcesByChunkIds(chunkIds)).thenReturn(manualOnly);
    }

    @Test
    void should_throwBadRequest_when_delete_given_nullChunkId() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.delete(new DeleteChunkCommand(null)));
        verifyNoInteractions(chunkRepository, mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwNotFound_when_delete_given_missingChunk() {
        // given：切片行物理不存在（投影无该键）
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwNotFound_when_delete_given_documentRowAlreadyCollected() {
        // given：文档行已收口物理删除（行不存在 = 404，无 DELETED 判定）
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwNotFound_when_delete_given_knowledgeBaseRowMissing() {
        // given：知识库行不存在（已收口）→ 404
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwBadRequest_when_delete_given_deletingKnowledgeBase() {
        // given：知识库处于删除流程（DELETING），内容面操作一律拒绝
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwConflict_when_delete_given_deletingDocument() {
        // given：文档已进入删除链（DELETING），拒绝与分批清退赛跑
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.DELETING)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_throwConflict_when_delete_given_deleteFailedDocument() {
        // given：删除失败待重删（DELETE_FAILED）同样拒绝人工切片删除
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.DELETE_FAILED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verifyNoInteractions(mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_recycleMediaObjectBeforeTransaction_when_delete_given_exclusivelyReferencedImage() {
        // given：图片切片独占引用（同文档无其他存活切片引用同一对象）
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID))
                .thenReturn(Map.of(CHUNK_ID, MEDIA_REFERENCE_JSON));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);
        doNothing().when(mediaImageCleanupService).recycleChunkMediaObject(MEDIA_REFERENCE);

        // when
        service.delete(new DeleteChunkCommand(CHUNK_ID));

        // then：Storage 先删（事务外回收对象）→ DB 后删（原语事务，回写分块计数；
        // 图谱收敛由批次数据推导，经来源准入后本批恒为人工块、原语自动跳过收敛）
        InOrder order = inOrder(mediaImageCleanupService, chunkPhysicalDeleteService);
        order.verify(mediaImageCleanupService).recycleChunkMediaObject(MEDIA_REFERENCE);
        order.verify(chunkPhysicalDeleteService).deleteChunks(List.of(CHUNK_ID), null, true);
    }

    @Test
    void should_skipObjectRecycle_when_delete_given_mediaSharedByOtherLivingChunk() {
        // given：同文档另一存活切片引用同一图片对象 → 对象不删，直接进入事务
        Long survivingChunkId = CHUNK_ID + 1;
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID))
                .thenReturn(Map.of(CHUNK_ID, MEDIA_REFERENCE_JSON, survivingChunkId, MEDIA_REFERENCE_JSON));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);

        // when
        service.delete(new DeleteChunkCommand(CHUNK_ID));

        // then
        verify(mediaImageCleanupService, never()).recycleChunkMediaObject(any());
        verify(chunkPhysicalDeleteService).deleteChunks(List.of(CHUNK_ID), null, true);
    }

    @Test
    void should_keepDbUntouched_when_delete_given_mediaRecycleFails() {
        // given：对象存储回收失败（硬顺序：失败即终止）
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID))
                .thenReturn(Map.of(CHUNK_ID, MEDIA_REFERENCE_JSON));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);
        doThrow(new DeepDataAgentException("切片图片对象回收失败，数据库未做任何变更，请重新执行删除"))
                .when(mediaImageCleanupService).recycleChunkMediaObject(MEDIA_REFERENCE);

        // when // then：业务异常上抛，删除原语零触达（数据库一行未动）
        assertThrows(DeepDataAgentException.class, () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        verify(chunkPhysicalDeleteService, never()).deleteChunks(any(), any(), anyBoolean());
    }

    @Test
    void should_stillDeleteInTransaction_when_delete_given_objectAlreadyMissing() {
        // given：对象不存在视为成功（端口删除幂等，回收 void 不抛异常）——重删自愈半态
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID))
                .thenReturn(Map.of(CHUNK_ID, MEDIA_REFERENCE_JSON));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);
        doNothing().when(mediaImageCleanupService).recycleChunkMediaObject(MEDIA_REFERENCE);

        // when
        service.delete(new DeleteChunkCommand(CHUNK_ID));

        // then：DB 清退照常完成
        verify(chunkPhysicalDeleteService).deleteChunks(List.of(CHUNK_ID), null, true);
    }

    @Test
    void should_deleteWithoutMediaRecycle_when_delete_given_textChunkWithoutReference() {
        // given：文本切片无图片引用（媒体投影为空映射）
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID)).thenReturn(Map.of());

        // when
        service.delete(new DeleteChunkCommand(CHUNK_ID));

        // then：不触达对象回收，直接进原语事务；
        // 原语 3 参口径——分块计数回写开启（true），图谱收敛不再由调用方声明
        // （经来源准入后本批恒为人工块，原语按「切片 → 来源」投影自行推导为跳过收敛；
        // 图谱收敛契约 DocumentGraphConvergenceApi 属原语内部依赖，不在本服务依赖面上）
        verify(mediaImageCleanupService, never()).recycleChunkMediaObject(any());
        verify(chunkPhysicalDeleteService).deleteChunks(eq(List.of(CHUNK_ID)), isNull(), eq(true));
    }

    /**
     * 删除人工块成功：来源准入通过后原语 3 参调用（计数回写开启），全程零媒体交互。
     */
    @Test
    void should_deleteManualChunk_when_delete_given_allManualSourceBatch() {
        // given：本批全为「人工新增」来源（stubDeleteGatePassThrough 已含来源准入桩）
        stubDeleteGatePassThrough();

        // when
        service.delete(new DeleteChunkCommand(CHUNK_ID));

        // then：原语以 3 参签名收到本批（operator 为 null，计数回写开启）
        verify(chunkPhysicalDeleteService).deleteChunks(List.of(CHUNK_ID), null, true);
        verifyNoInteractions(mediaImageCleanupService);
    }

    /**
     * 删除解析块被拒绝：来源准入拦截「解析产生」的切片（单独删除会使图谱账本引用已消失分块），
     * 附「重新解析 / 删除文档」引导文案，数据库与对象存储零触达。
     */
    @Test
    void should_rejectDeletion_when_delete_given_parsedSourceChunk() {
        // given：闸门通过，但来源投影显示该切片为「解析产生」
        when(chunkRepository.findDocumentIdsByChunkIds(List.of(CHUNK_ID))).thenReturn(Map.of(CHUNK_ID, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.findSourcesByChunkIds(List.of(CHUNK_ID)))
                .thenReturn(Map.of(CHUNK_ID, ChunkSource.PARSED));

        // when // then：400 拒绝并附引导，原语与媒体回收零触达
        DeepDataAgentException error = assertThrows(DeepDataAgentException.class,
                () -> service.delete(new DeleteChunkCommand(CHUNK_ID)));
        assertTrue(error.getMessage().contains("解析产生的切片不支持单独删除"));
        assertTrue(error.getMessage().contains("重新解析"));
        verify(chunkPhysicalDeleteService, never()).deleteChunks(any(), any(), anyBoolean());
        verifyNoInteractions(mediaImageCleanupService);
    }

    /**
     * 混批整批拒绝：一批中仅含一个「解析产生」的切片即整批拒绝（不做部分放行），
     * 人工块同样不删——由用户剔除解析块后重删。
     */
    @Test
    void should_rejectWholeBatch_when_deleteBatch_given_mixedSourceBatch() {
        // given：CHUNK_ID 为人工块、CHUNK_ID+1 为解析块（同文档混批）
        Long parsedChunkId = CHUNK_ID + 1;
        List<Long> batchIds = List.of(CHUNK_ID, parsedChunkId);
        when(chunkRepository.findDocumentIdsByChunkIds(batchIds))
                .thenReturn(Map.of(CHUNK_ID, DOC_ID, parsedChunkId, DOC_ID));
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 3, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.findSourcesByChunkIds(batchIds))
                .thenReturn(Map.of(CHUNK_ID, ChunkSource.MANUAL, parsedChunkId, ChunkSource.PARSED));

        // when // then：整批拒绝，原语零触达（人工块也不删）
        assertThrows(DeepDataAgentException.class,
                () -> service.deleteBatch(new DeleteChunksCommand(batchIds)));
        verify(chunkPhysicalDeleteService, never()).deleteChunks(any(), any(), anyBoolean());
    }

    @Test
    void should_throwBadRequest_when_deleteBatch_given_emptyIds() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.deleteBatch(new DeleteChunksCommand(List.of())));
        verifyNoInteractions(chunkRepository, mediaImageCleanupService, chunkPhysicalDeleteService);
    }

    @Test
    void should_deduplicateAndDropNullIds_when_deleteBatch_given_duplicateIds() {
        // given：重复与 null 元素混合入参
        stubDeleteGatePassThrough();
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID)).thenReturn(Map.of());

        // when
        service.deleteBatch(new DeleteChunksCommand(Arrays.asList(CHUNK_ID, null, CHUNK_ID)));

        // then：去重清洗后单批执行，原语收到仅含真实ID的集合
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkPhysicalDeleteService).deleteChunks(captor.capture(), isNull(), eq(true));
        assertEquals(List.of(CHUNK_ID), captor.getValue());
    }

    /**
     * 构造「按批等值应答」的切片→文档投影桩（分批用例：任一批次内全部ID同属测试文档）。
     */
    private void stubDocumentProjectionPerBatch() {
        when(chunkRepository.findDocumentIdsByChunkIds(anyList())).thenAnswer(invocation -> {
            Map<Long, Long> projection = new LinkedHashMap<>();
            for (Long chunkId : invocation.<List<Long>>getArgument(0)) {
                projection.put(chunkId, DOC_ID);
            }
            return projection;
        });
    }

    /**
     * 构造「按批等值应答」的切片→来源投影桩（分批用例：全为「人工新增」，来源准入通过）。
     */
    private void stubManualSourcePerBatch() {
        when(chunkRepository.findSourcesByChunkIds(anyList())).thenAnswer(invocation -> {
            Map<Long, ChunkSource> projection = new LinkedHashMap<>();
            for (Long chunkId : invocation.<List<Long>>getArgument(0)) {
                projection.put(chunkId, ChunkSource.MANUAL);
            }
            return projection;
        });
    }

    @Test
    void should_splitIntoBatchesOfFiveHundred_when_deleteBatch_given_1001Ids() {
        // given：1001 条切片同属一个文档且全为人工来源，按 500 条/批切分为三批
        List<Long> allIds = IntStream.rangeClosed(1, 1001).mapToObj(i -> (long) i).collect(Collectors.toList());
        stubDocumentProjectionPerBatch();
        stubManualSourcePerBatch();
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 9, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID)).thenReturn(Map.of());

        // when
        service.deleteBatch(new DeleteChunksCommand(allIds));

        // then：三批各自独立事务，批次规模为 500 / 500 / 1
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkPhysicalDeleteService, times(3)).deleteChunks(captor.capture(), isNull(), eq(true));
        assertEquals(List.of(500, 500, 1),
                captor.getAllValues().stream().map(List::size).collect(Collectors.toList()));
    }

    @Test
    void should_stopRemainingBatches_when_deleteBatch_given_secondBatchFails() {
        // given：第二批清退抛错——终止剩余批次（零重试），已提交第一批不回滚
        List<Long> allIds = IntStream.rangeClosed(1, 1001).mapToObj(i -> (long) i).collect(Collectors.toList());
        stubDocumentProjectionPerBatch();
        stubManualSourcePerBatch();
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 9, DocumentStatus.PROCESSED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.findMediaReferencesByDocumentId(DOC_ID)).thenReturn(Map.of());
        doNothing()
                .doThrow(new IllegalStateException("第二批清退故障"))
                .when(chunkPhysicalDeleteService).deleteChunks(anyList(), isNull(), eq(true));

        // when // then：异常上抛，仅前两批被触达（第二批即抛即停，第三批不再执行）
        assertThrows(IllegalStateException.class, () -> service.deleteBatch(new DeleteChunksCommand(allIds)));
        verify(chunkPhysicalDeleteService, times(2)).deleteChunks(anyList(), isNull(), eq(true));
    }

    @Test
    void should_returnChunk_when_get_given_existingId() {
        // given
        Chunk existing = buildChunk("切片内容", 30);
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(existing));

        // when
        Chunk result = service.get(CHUNK_ID);

        // then
        assertEquals(existing, result);
    }

    @Test
    void should_throwNotFound_when_get_given_missingId() {
        // given
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get(CHUNK_ID));
    }

    @Test
    void should_passQueryConditions_when_list_given_fullConditions() {
        // given
        List<Chunk> pageData = List.of(buildChunk("切片内容", 30));
        when(chunkRepository.findByKbId(KB_ID, DOC_ID, 1, "关键字", 2, 50)).thenReturn(pageData);

        // when
        List<Chunk> result = service.list(new ListChunkQuery(KB_ID, DOC_ID, 1, "关键字", 2, 50));

        // then
        assertEquals(pageData, result);
        verify(chunkRepository).findByKbId(KB_ID, DOC_ID, 1, "关键字", 2, 50);
    }

    @Test
    void should_returnCount_when_count_given_conditions() {
        // given
        when(chunkRepository.countByKbId(KB_ID, DOC_ID, 1, "关键字")).thenReturn(9L);

        // when
        long total = service.count(KB_ID, DOC_ID, 1, "关键字");

        // then
        assertEquals(9L, total);
    }

    @Test
    void should_useGivenPaging_when_listByDocument_given_validPaging() {
        // given
        List<Chunk> pageData = List.of(buildChunk("切片内容", 30));
        when(chunkRepository.findByDocumentId(DOC_ID, 3, 10)).thenReturn(pageData);

        // when
        List<Chunk> result = service.listByDocument(DOC_ID, 3, 10);

        // then
        assertEquals(pageData, result);
    }

    @Test
    void should_fallbackDefaultPaging_when_listByDocument_given_illegalPaging() {
        // given
        when(chunkRepository.findByDocumentId(DOC_ID, 1, 20)).thenReturn(List.of());

        // when
        List<Chunk> result = service.listByDocument(DOC_ID, 0, -5);

        // then
        assertEquals(List.of(), result);
        verify(chunkRepository).findByDocumentId(DOC_ID, 1, 20);
    }

    private ChunkDraft buildDraft(int sequence, String embedding) {
        return new ChunkDraft(sequence, "切片内容-" + sequence, 30, null, "TEXT", embedding);
    }

    private Chunk persistedChunk(int sequence, long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return Chunk.restore(id, KB_ID, DOC_ID, sequence, 30, "切片内容-" + sequence,
                null, ChunkContentType.TEXT, "手册.pdf", null, ChunkSource.PARSED, now, now);
    }

    /**
     * 构建携带指定元数据 JSON 的图片切片草稿（用于验证 s3_file 一等列提取口径）。
     */
    private ChunkDraft buildImageDraft(int sequence, String metadata) {
        return new ChunkDraft(sequence, "图片描述-" + sequence, 30, metadata, "IMAGE", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_replaceChunksWithoutTouchingStatus_when_replaceForDocument_given_validDrafts() {
        // given
        List<ChunkDraft> drafts = List.of(buildDraft(1, "[0.1,0.2]"), buildDraft(2, null));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 9)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 2001L), persistedChunk(2, 2002L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Map<Integer, Long> identityMap = service.replaceForDocument(DOC_ID, drafts, 7L);

        // then：主键映射在落库事务内回传（与 saveBatch 回读结果同源），消费方无需回查
        assertEquals(2, identityMap.size());
        assertEquals(2001L, identityMap.get(1).longValue());
        assertEquals(2002L, identityMap.get(2).longValue());
        // 清退旧数据 + 批写新切片 + 全量非空白切片落派生表示（全文现算、向量随有向量的切片） + 计数原子回写
        verify(chunkRepository).deleteByDocumentId(DOC_ID);
        verify(chunkRepresentationRepository).deleteByDocumentId(DOC_ID);
        verify(chunkRepository).saveBatch(anyList(), eq("7"));
        ArgumentCaptor<List<ChunkRepresentation>> repCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepresentationRepository).saveBatch(repCaptor.capture(), eq("7"));
        assertEquals(2, repCaptor.getValue().size());
        assertEquals(2001L, repCaptor.getValue().get(0).chunkId());
        assertEquals(2002L, repCaptor.getValue().get(1).chunkId());
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(docCaptor.capture());
        assertEquals(2, docCaptor.getValue().chunkCount().intValue());
        // 落库只回写分块计数，不写文档状态：切片写入成功不等于摄入完成（其后还有抽取与图合并），
        // 成功终态由摄入管线收尾经 markProcessed 条件流转写入
        assertEquals(DocumentStatus.PROCESSING, docCaptor.getValue().status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_replaceIdempotently_when_replaceForDocument_given_repeatedCall() {
        // given
        List<ChunkDraft> drafts = List.of(buildDraft(1, "[0.1,0.2]"));
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 2)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), isNull()))
                .thenReturn(List.of(persistedChunk(1, 3001L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, drafts, null);
        service.replaceForDocument(DOC_ID, drafts, null);

        // then：每次都先清退再重建，计数以实际切片为准（幂等可重复回写）
        verify(chunkRepository, times(2)).deleteByDocumentId(DOC_ID);
        verify(chunkRepresentationRepository, times(2)).deleteByDocumentId(DOC_ID);
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(2)).update(docCaptor.capture());
        docCaptor.getAllValues().forEach(document -> assertEquals(1, document.chunkCount().intValue()));
    }

    @Test
    void should_rejectWholeBatch_when_replaceForDocument_given_emptyDocumentId() {
        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> service.replaceForDocument(null, List.of(buildDraft(1, null)), 7L));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void should_rejectWholeBatch_when_replaceForDocument_given_emptyDrafts() {
        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(), 7L));
        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(chunkRepository, never()).deleteByDocumentId(any());
    }

    @Test
    void should_acceptDraftsBeyondFormerCap_when_replaceForDocument_given_1001Drafts() {
        // given：1001 条（历史上会被 1000 数量上限整批拒绝；上限已移除，应正常过校验并整批落库）
        List<ChunkDraft> drafts = IntStream.rangeClosed(1, 1001)
                .mapToObj(seq -> buildDraft(seq, null))
                .toList();
        List<Chunk> persisted = IntStream.rangeClosed(1, 1001)
                .mapToObj(seq -> persistedChunk(seq, 5000L + seq))
                .toList();
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7"))).thenReturn(persisted);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, drafts, 7L);

        // then：校验放行，同一事务内整批一次批写
        ArgumentCaptor<List<Chunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveBatch(chunkCaptor.capture(), eq("7"));
        assertEquals(1001, chunkCaptor.getValue().size());
    }

    @Test
    void should_rejectWholeBatch_when_replaceForDocument_given_duplicateSequence() {
        // given
        List<ChunkDraft> drafts = List.of(buildDraft(1, null), buildDraft(1, null));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.replaceForDocument(DOC_ID, drafts, 7L));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void should_keepOriginChunks_when_replaceForDocument_given_documentNotExist() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verify(chunkRepository, never()).saveBatch(anyList(), any());
    }

    @Test
    void should_keepOriginChunks_when_replaceForDocument_given_deletingKnowledgeBase() {
        // given
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 9)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verify(chunkRepository, never()).saveBatch(anyList(), any());
    }

    @Test
    void should_rejectWriteBack_when_replaceForDocument_given_deletingDocument() {
        // given：文档已进入删除链（DELETING），切片正被分批清退，回写必须整批拒绝
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 9, DocumentStatus.DELETING)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verifyNoChunkWriteInteractions();
    }

    @Test
    void should_rejectWriteBack_when_replaceForDocument_given_deleteFailedDocument() {
        // given：删除失败待重试（DELETE_FAILED）同样禁止摄入回写，避免复活为 PROCESSED
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 9, DocumentStatus.DELETE_FAILED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verifyNoChunkWriteInteractions();
    }

    @Test
    void should_rejectWriteBack_when_replaceForDocument_given_pendingDocument() {
        // given：PENDING 表示无人领取（或已被重新置为待处理），处理中前置闸门必须拒绝
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 0, DocumentStatus.PENDING)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verifyNoChunkWriteInteractions();
    }

    @Test
    void should_rejectWriteBack_when_replaceForDocument_given_failedDocument() {
        // given：失联实例的僵尸回写——文档已被超时回收置 FAILED，既有切片不得被覆盖
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 0, DocumentStatus.FAILED)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertThrows(ResourceConflictException.class,
                () -> service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L));
        verifyNoChunkWriteInteractions();
    }

    @Test
    void should_allowWriteBack_when_replaceForDocument_given_processingDocument() {
        // given：PROCESSING（已被 worker 认领）不在删除链，回写正常放行
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocumentWithStatus(KB_ID, 0, DocumentStatus.PROCESSING)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 7002L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L);

        // then：闸门放行，正常清退重建，但文档状态保持 PROCESSING（终态由管线收尾写入）
        verify(chunkRepository).deleteByDocumentId(DOC_ID);
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(docCaptor.capture());
        assertEquals(DocumentStatus.PROCESSING, docCaptor.getValue().status());
    }

    /** 校验删除链闸门拒绝后事务内无任何写入交互（清退与重建均未触达）。 */
    private void verifyNoChunkWriteInteractions() {
        verify(chunkRepository, never()).deleteByDocumentId(any());
        verify(chunkRepresentationRepository, never()).deleteByDocumentId(any());
        verify(chunkRepository, never()).saveBatch(anyList(), any());
        verify(chunkRepresentationRepository, never()).saveBatch(anyList(), any());
        verify(documentRepository, never()).update(any());
    }

    @Test
    void should_splitIntoBatches_when_replaceForDocument_given_largeDraftSet() {
        // given：一批 1000 条，saveBatch 内部分片，此处只验证服务层单次调用批写
        List<ChunkDraft> drafts = IntStream.rangeClosed(1, 1000)
                .mapToObj(seq -> buildDraft(seq, null))
                .toList();
        List<Chunk> persisted = IntStream.rangeClosed(1, 1000)
                .mapToObj(seq -> persistedChunk(seq, 5000L + seq))
                .toList();
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7"))).thenReturn(persisted);
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, drafts, 7L);

        // then：切片主表一次批写；草稿均携带切片正文 → 全文现算派生表示同数落库（向量仅随有向量的切片）
        ArgumentCaptor<List<Chunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveBatch(chunkCaptor.capture(), eq("7"));
        assertEquals(1000, chunkCaptor.getValue().size());
        ArgumentCaptor<List<ChunkRepresentation>> repCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepresentationRepository).saveBatch(repCaptor.capture(), eq("7"));
        assertEquals(1000, repCaptor.getValue().size());
    }

    @Test
    void should_transitPendingToProcessing_when_markProcessing_given_claimableDocument() {
        // given：出队领取 = 条件更新 PENDING→PROCESSING，errorMessage 传 null 即同批清除残留失败原因
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.PROCESSING, null)).thenReturn(true);

        // when
        boolean claimed = service.markProcessing(DOC_ID);

        // then
        assertTrue(claimed);
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PENDING),
                DocumentStatus.PROCESSING, null);
    }

    @Test
    void should_returnFalse_when_markProcessing_given_casMiss() {
        // given：文档已被删除链置 DELETING 或已被启动清理置 FAILED，CAS 未命中属正常静默丢弃
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.PROCESSING), isNull()))
                .thenReturn(false);

        // when
        boolean claimed = service.markProcessing(DOC_ID);

        // then
        assertFalse(claimed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_lockPendingOnlySourceStatus_when_markProcessing_given_casWhitelistLocked() {
        // given：捕获 CAS 使用的状态白名单集合本身
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.PROCESSING), isNull()))
                .thenReturn(false);

        // when
        service.markProcessing(DOC_ID);

        // then：白名单锁定为 {PENDING}——FAILED 须经用户重新解析回到 PENDING，不可被队列直接认领
        ArgumentCaptor<Set<DocumentStatus>> statusCaptor = ArgumentCaptor.forClass(Set.class);
        verify(documentRepository).transitStatus(eq(DOC_ID), statusCaptor.capture(),
                eq(DocumentStatus.PROCESSING), isNull());
        assertEquals(Set.of(DocumentStatus.PENDING), statusCaptor.getValue());
    }

    @Test
    void should_throwBadRequest_when_markProcessing_given_nullDocumentId() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.markProcessing(null));
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_coverBothNonTerminalStatuses_when_failNonTerminal_given_startupCleanup() {
        // given：启动清理一次性把 PENDING/PROCESSING 收敛为 FAILED，返回影响行数
        when(documentRepository.failNonTerminal(anySet(), eq(DocumentStatus.FAILED),
                eq("[STARTUP] 服务重启中断，请重新解析"))).thenReturn(3);

        // when
        int failed = service.failNonTerminal("[STARTUP] 服务重启中断，请重新解析");

        // then：源状态集合锁定为两非终态，目标态与原因原样透传
        assertEquals(3, failed);
        ArgumentCaptor<Set<DocumentStatus>> statusCaptor = ArgumentCaptor.forClass(Set.class);
        verify(documentRepository).failNonTerminal(statusCaptor.capture(), eq(DocumentStatus.FAILED),
                eq("[STARTUP] 服务重启中断，请重新解析"));
        assertEquals(Set.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING), statusCaptor.getValue());
    }

    @Test
    void should_returnZero_when_failNonTerminal_given_noResidualRows() {
        // given：无崩溃残留时批量 UPDATE 天然影响 0 行（幂等）
        when(documentRepository.failNonTerminal(anySet(), eq(DocumentStatus.FAILED), any()))
                .thenReturn(0);

        // when // then
        assertEquals(0, service.failNonTerminal("[STARTUP] 服务重启中断，请重新解析"));
    }

    @Test
    void should_throwBadRequest_when_failNonTerminal_given_blankErrorMessage() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.failNonTerminal("  "));
        verify(documentRepository, never()).failNonTerminal(anySet(), any(), any());
    }

    @Test
    void should_transitToFailedWithReason_when_markFailed_given_processingDocument() {
        // given
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.FAILED, "解析超时")).thenReturn(true);

        // when
        service.markFailed(DOC_ID, "解析超时");

        // then
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.FAILED, "解析超时");
    }

    @Test
    void should_ignoreCasMiss_when_markFailed_given_documentNoLongerProcessing() {
        // given：文档已不在 PROCESSING（如已被并发删除），CAS 未命中静默返回
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.FAILED), any()))
                .thenReturn(false);

        // when：不抛异常即为通过
        service.markFailed(DOC_ID, "向量化失败");

        // then
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.FAILED, "向量化失败");
    }

    @Test
    void should_throwBadRequest_when_markFailed_given_nullDocumentId() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.markFailed(null, "解析超时"));
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    void should_throwBadRequest_when_markFailed_given_blankErrorMessage() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.markFailed(DOC_ID, "   "));
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    void should_transitProcessingToProcessed_when_markProcessed_given_inFlightDocument() {
        // given：摄入管线完整结束时写入成功终态 = 条件更新 PROCESSING→PROCESSED，
        // errorMessage 传 null 即同批清除残留失败原因
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.PROCESSED, null)).thenReturn(true);

        // when
        boolean marked = service.markProcessed(DOC_ID);

        // then
        assertTrue(marked);
        verify(documentRepository).transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.PROCESSED, null);
    }

    @Test
    void should_returnFalse_when_markProcessed_given_casMiss() {
        // given：文档已被删除链置 DELETING 或已被用户重新解析置 PENDING，条件更新零行命中
        when(documentRepository.transitStatus(DOC_ID, Set.of(DocumentStatus.PROCESSING),
                DocumentStatus.PROCESSED, null)).thenReturn(false);

        // when
        boolean marked = service.markProcessed(DOC_ID);

        // then：未命中即静默放弃本次写入，状态归持有方（不回退、不覆盖）
        assertFalse(marked);
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_lockProcessingOnlySourceStatus_when_markProcessed_given_casWhitelistLocked() {
        // given：捕获 CAS 使用的状态白名单集合本身
        when(documentRepository.transitStatus(eq(DOC_ID), anySet(), eq(DocumentStatus.PROCESSED), isNull()))
                .thenReturn(false);

        // when
        service.markProcessed(DOC_ID);

        // then：白名单锁定为 {PROCESSING}——已脱离处理中状态的迟到置态不得复活覆盖
        ArgumentCaptor<Set<DocumentStatus>> statusCaptor = ArgumentCaptor.forClass(Set.class);
        verify(documentRepository).transitStatus(eq(DOC_ID), statusCaptor.capture(),
                eq(DocumentStatus.PROCESSED), isNull());
        assertEquals(Set.of(DocumentStatus.PROCESSING), statusCaptor.getValue());
    }

    @Test
    void should_throwBadRequest_when_markProcessed_given_nullDocumentId() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.markProcessed(null));
        verify(documentRepository, never()).transitStatus(any(), anySet(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_clearResidualErrorMessage_when_replaceForDocument_given_documentWithErrorMessage() {
        // given：失败原因残留的文档成功回写时，应同步清除残留（幂等兜底）
        List<ChunkDraft> drafts = List.of(buildDraft(1, null));
        when(documentRepository.findByIdForUpdate(DOC_ID))
                .thenReturn(Optional.of(buildDocument(KB_ID, 0, "解析失败：向量模型超时")));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 6001L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, drafts, 7L);

        // then：残留失败原因在落库时清除（幂等兜底），但文档状态保持 PROCESSING 不动
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).update(docCaptor.capture());
        assertEquals(DocumentStatus.PROCESSING, docCaptor.getValue().status());
        assertNull(docCaptor.getValue().errorMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_extractS3FileReference_when_replaceForDocument_given_imageDraftWithMediaMeta() {
        // given：图片切片草稿元数据携带媒体对象引用键（rag 摄入管线注入）
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 8001L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, List.of(buildImageDraft(1,
                "{\"page\":2,\"mediaObjectKey\":\"rag/1/img-1.png\"}")), 7L);

        // then：s3_file 一等列落库为仅 {objectKey} 形态（桶概念已退役）
        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveBatch(captor.capture(), eq("7"));
        assertEquals("{\"objectKey\":\"rag/1/img-1.png\"}", captor.getValue().get(0).s3File());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_writeNullS3File_when_replaceForDocument_given_textDraftWithoutMediaMeta() {
        // given：文本切片无媒体引用键，s3_file 属预期置空
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 8002L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.replaceForDocument(DOC_ID, List.of(buildDraft(1, null)), 7L);

        // then
        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveBatch(captor.capture(), eq("7"));
        assertNull(captor.getValue().get(0).s3File());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_writeNullS3File_when_replaceForDocument_given_malformedMediaMeta() {
        // given：非法 JSON / 引用键缺失一律宽松置空，绝不断整批回写
        when(documentRepository.findByIdForUpdate(DOC_ID)).thenReturn(Optional.of(buildDocument(KB_ID, 0)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(chunkRepository.saveBatch(anyList(), eq("7")))
                .thenReturn(List.of(persistedChunk(1, 8003L), persistedChunk(2, 8004L)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when：草稿1 非法 JSON，草稿2 缺 mediaObjectKey 引用键
        service.replaceForDocument(DOC_ID, List.of(
                buildImageDraft(1, "not-a-json{{"),
                buildImageDraft(2, "{\"page\":1}")), 7L);

        // then：整批仍然成功落库，两条切片 s3_file 均为空（meta 原样双写兜底）
        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveBatch(captor.capture(), eq("7"));
        assertEquals(2, captor.getValue().size());
        assertNull(captor.getValue().get(0).s3File());
        assertNull(captor.getValue().get(1).s3File());
        assertEquals("not-a-json{{", captor.getValue().get(0).originalItem());
    }

    // ==================== 切片媒体图片预览（openMedia） ====================

    /**
     * 构造携带媒体引用的切片（openMedia 场景专用）。
     *
     * @param s3File 媒体引用 JSON，可为 null
     * @return 切片聚合根
     */
    private Chunk buildMediaChunk(String s3File) {
        OffsetDateTime now = OffsetDateTime.now();
        return Chunk.restore(CHUNK_ID, KB_ID, DOC_ID, 1, 10, "图片切片", null,
                null, "手册.pdf", s3File, ChunkSource.PARSED, now, now);
    }

    @Test
    void should_throwNotFound_when_openMedia_given_chunkMissing() {
        // given：切片不存在（闸门第一步即拒绝，不触达知识库与对象存储）
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.empty());

        // when // then：404「切片不存在」
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.openMedia(CHUNK_ID));
        assertTrue(exception.getMessage().contains("切片不存在"));
        verifyNoInteractions(kbAssetStoragePort);
    }

    @Test
    void should_returnMediaStream_when_openMedia_given_validReferenceAndObjectExists() throws Exception {
        // given：切片登记媒体引用，库 ACTIVE，对象存储命中对象
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildMediaChunk(MEDIA_REFERENCE_JSON)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);
        when(kbAssetStoragePort.open("rag/10/100/images/a.png")).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L)));

        // when
        ChunkMediaResult result = service.openMedia(CHUNK_ID);

        // then：contentType 按对象键扩展名推断（.png → image/png），字节数取 open 精确值
        assertEquals("image/png", result.contentType());
        assertEquals(3L, result.contentLength());
        try (InputStream content = result.content()) {
            assertArrayEquals(new byte[] {1, 2, 3}, content.readAllBytes());
        }
        verify(kbAssetStoragePort).open("rag/10/100/images/a.png");
    }

    @Test
    void should_throwNotFound_when_openMedia_given_chunkWithoutMediaReference() {
        // given：文本切片无媒体引用（解析结果为 null）
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildMediaChunk(null)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then：404「切片无媒体图片引用」，不触碰对象存储
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.openMedia(CHUNK_ID));
        assertTrue(exception.getMessage().contains("切片无媒体图片引用"));
        verify(kbAssetStoragePort, never()).open(any());
    }

    @Test
    void should_throwNotFound_when_openMedia_given_mediaObjectMissing() {
        // given：引用合法但对象存储中对象不存在（open 返回 empty）
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildMediaChunk(MEDIA_REFERENCE_JSON)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));
        when(mediaImageCleanupService.parseMediaReference(MEDIA_REFERENCE_JSON)).thenReturn(MEDIA_REFERENCE);
        when(kbAssetStoragePort.open("rag/10/100/images/a.png")).thenReturn(Optional.empty());

        // when // then：404「切片媒体图片对象不存在」
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.openMedia(CHUNK_ID));
        assertTrue(exception.getMessage().contains("切片媒体图片对象不存在"));
    }

    @Test
    void should_throwBadRequest_when_openMedia_given_knowledgeBaseNotActive() {
        // given：所属知识库处于删除流程（非 ACTIVE），前置闸门拒绝且不触碰对象存储
        when(chunkRepository.findById(CHUNK_ID)).thenReturn(Optional.of(buildMediaChunk(MEDIA_REFERENCE_JSON)));
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.openMedia(CHUNK_ID));
        assertTrue(exception.getMessage().contains("知识库不可用"));
        verifyNoInteractions(kbAssetStoragePort);
    }

}

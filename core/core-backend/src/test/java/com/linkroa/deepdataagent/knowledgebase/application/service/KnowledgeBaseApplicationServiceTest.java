package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupTaskSubmitter;
import com.linkroa.deepdataagent.knowledgebase.application.command.CreateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateEntityTypeConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateRetrievalConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.contract.RetrievalConfigDTO;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListKnowledgeBaseQuery;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseApplicationService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseApplicationServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private KbCleanupTaskSubmitter kbCleanupTaskSubmitter;
    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    @InjectMocks
    private KnowledgeBaseApplicationService service;

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

    private KnowledgeBase buildKb(Long id, String name, LifecycleStatus status) {
        return buildKb(id, name, status, null);
    }

    private KnowledgeBase buildKb(Long id, String name, LifecycleStatus status, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(id, name, "原始描述", "Chinese", status,
                errorMessage, "{\"engine\":\"builtin\"}", "{\"matchRule\":\"NAME\"}", "{\"mode\":\"HYBRID\"}",
                "{\"model\":\"bge-m3\"}", "{\"vlm\":\"qwen-vl\"}", "{\"types\":[\"PRODUCT\"]}", now, now);
    }

    @Test
    void should_saveKnowledgeBase_when_create_given_uniqueName() {
        // given
        when(knowledgeBaseRepository.findByName("产品手册")).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("产品手册", "描述", "Chinese",
                null, null, null, null, null, null);

        // when
        KnowledgeBase saved = service.create(command);

        // then
        assertEquals("产品手册", saved.name());
        assertEquals(LifecycleStatus.ACTIVE, saved.lifecycleStatus());
        verify(knowledgeBaseRepository).save(any(KnowledgeBase.class));
    }

    @Test
    void should_saveKnowledgeBaseWithLanguage_when_create_given_legalJapaneseLanguage() {
        // given：语言经独立入参传递（真相源=language 列），rag_engine_config 不再携带语言键
        when(knowledgeBaseRepository.findByName("日文文库")).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("日文文库", "描述", "Japanese",
                "{\"engineType\":\"DOCUMENT_ENGINE\"}",
                null, null, null, null, null);

        // when
        KnowledgeBase saved = service.create(command);

        // then：值域校验通过，语言落聚合列字段、引擎配置原样持久化
        assertEquals("Japanese", saved.language());
        assertEquals("{\"engineType\":\"DOCUMENT_ENGINE\"}", saved.ragEngineConfig());
        verify(knowledgeBaseRepository).save(any(KnowledgeBase.class));
    }

    @Test
    void should_fallbackDefaultChinese_when_create_given_blankLanguageArgument() {
        // given：语言入参空白 → 聚合 create 显式兜底 Chinese（不依赖列库默认值）
        when(knowledgeBaseRepository.findByName("默认文库")).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("默认文库", "描述", null,
                "{\"engineType\":\"DOCUMENT_ENGINE\"}", null, null, null, null, null);

        // when
        KnowledgeBase saved = service.create(command);

        // then
        assertNotNull(saved);
        assertEquals("Chinese", saved.language());
    }

    @Test
    void should_throwBadRequest_when_create_given_unlistedLanguagePortuguese() {
        // given：Portuguese 不在 11 全名值域
        when(knowledgeBaseRepository.findByName("葡语文库")).thenReturn(Optional.empty());
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("葡语文库", "描述", "Portuguese",
                "{\"engineType\":\"DOCUMENT_ENGINE\"}",
                null, null, null, null, null);

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.create(command));

        // then：明确拒绝并提示合法值域，禁止静默回落后保存成功
        assertTrue(exception.getMessage().contains("Portuguese"));
        assertTrue(exception.getMessage().contains("English"));
        assertTrue(exception.getMessage().contains("Dutch"));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_create_given_bareLanguageCodeJa() {
        // given：历史裸语言码同样拒绝（值域为全名）
        when(knowledgeBaseRepository.findByName("裸码文库")).thenReturn(Optional.empty());
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("裸码文库", "描述", "ja",
                null, null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.create(command));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_create_given_mediaEngineType() {
        // given：本期仅支持文档引擎，创建携带 MEDIA_ENGINE 应入口拒绝
        when(knowledgeBaseRepository.findByName("媒体文库")).thenReturn(Optional.empty());
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("媒体文库", "描述", "Chinese",
                "{\"engineType\":\"MEDIA_ENGINE\"}", null, null, null, null, null);

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.create(command));

        // then：400 提示本期不提供，知识库不落库
        assertTrue(exception.getMessage().contains("本期不提供该引擎类型"));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequest_when_update_given_mediaEngineType() {
        // given：更新把引擎类型改回 MEDIA_ENGINE 同样前置拒绝，不开事务不读行
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Chinese",
                "{\"engineType\":\"MEDIA_ENGINE\"}", null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.update(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequest_when_update_given_unlistedLanguageBeforeTransaction() {
        // given：更新入口同样强校验语言值域，且校验前置于事务不开库不读行
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Portuguese",
                "{\"engineType\":\"DOCUMENT_ENGINE\"}", null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.update(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_persistLanguage_when_update_given_legalEnglishLanguage() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "English",
                "{\"engineType\":\"DOCUMENT_ENGINE\"}",
                null, null, null, null, null);

        // when
        KnowledgeBase updated = service.update(command);

        // then：合法语言全名替换至聚合列字段，引擎配置整段替换
        assertEquals("English", updated.language());
        assertEquals("{\"engineType\":\"DOCUMENT_ENGINE\"}", updated.ragEngineConfig());
    }

    @Test
    void should_throwBadRequestWithoutSave_when_create_given_illegalChunkStrategy() {
        // given：创建携带库级分块策略，但非 GENERAL 模式带了参数
        when(knowledgeBaseRepository.findByName("分块文库")).thenReturn(Optional.empty());
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("分块文库", "描述", "Chinese",
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"LAWS\","
                        + "\"modeConfig\":{\"params\":{\"chunk_token_num\":1024}}}}",
                null, null, null, null, null);

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.create(command));

        // then：400 指明仅 GENERAL 支持参数配置，校验前置于事务，知识库不落库
        assertTrue(exception.getMessage().contains("仅 GENERAL 模式支持分块参数配置"));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void should_throwBadRequestWithoutUpdate_when_update_given_illegalChunkStrategy() {
        // given：更新把库级分块模式的 token 预算写成 0
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Chinese",
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                        + "\"modeConfig\":{\"params\":{\"chunk_token_num\":0}}}}",
                null, null, null, null, null);

        // when // then：不开事务不读行，不存在半写状态
        assertThrows(DeepDataAgentException.class, () -> service.update(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_persistChunkStrategy_when_update_given_legacyDeprecatedKeys() {
        // given：存量库配置的分块参数含历史废弃键，用户编辑其他字段后保存
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String ragEngineConfig = "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                + "\"modeConfig\":{\"params\":{\"chunk_token_num\":1024,\"enable_children\":true}}}}";
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Chinese",
                ragEngineConfig, null, null, null, null, null);

        // when
        KnowledgeBase updated = service.update(command);

        // then：废弃键忽略不报错，配置整段原样保存（不回填、不清理）
        assertEquals(ragEngineConfig, updated.ragEngineConfig());
    }

    @Test
    void should_throwConflict_when_create_given_duplicatedName() {
        // given
        when(knowledgeBaseRepository.findByName("产品手册"))
                .thenReturn(Optional.of(buildKb(9L, "产品手册", LifecycleStatus.ACTIVE)));
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("产品手册", "描述", "Chinese",
                null, null, null, null, null, null);

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.create(command));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void should_keepOriginalConfig_when_update_given_blankCommandFields() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Chinese",
                null, null, null, null, null, null);

        // when
        KnowledgeBase updated = service.update(command);

        // then
        assertEquals("产品手册", updated.name());
        assertEquals("{\"mode\":\"HYBRID\"}", updated.retrievalStrategy());
        verify(knowledgeBaseRepository, never()).findByName(any());
    }

    @Test
    void should_ignoreEmbeddingConfig_when_update_given_changedEmbeddingConfig() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, null, null, "Chinese",
                null, null, null, "{\"model\":\"text-embedding-3\"}", null, null);

        // when
        KnowledgeBase updated = service.update(command);

        // then
        assertEquals("{\"model\":\"bge-m3\"}", updated.embeddingConfig());
    }

    @Test
    void should_throwConflict_when_update_given_nameOccupiedByOther() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.findByName("新名称"))
                .thenReturn(Optional.of(buildKb(2L, "新名称", LifecycleStatus.ACTIVE)));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, "新名称", null, "Chinese",
                null, null, null, null, null, null);

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.update(command));
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_skipUniqueCheck_when_update_given_nameUnchanged() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, "产品手册", "新描述", "Chinese",
                null, null, null, null, null, null);

        // when
        KnowledgeBase updated = service.update(command);

        // then
        assertEquals("新描述", updated.description());
        verify(knowledgeBaseRepository, never()).findByName(any());
    }

    @Test
    void should_throwNotFound_when_update_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(404L, "新名称", null, "Chinese",
                null, null, null, null, null, null);

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.update(command));
    }

    @Test
    void should_throwBadRequest_when_update_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETING)));
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(1L, "新名称", null, "Chinese",
                null, null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.update(command));
    }

    @Test
    void should_transitDeletingAndSubmitCleanup_when_delete_given_activeWithoutProcessingDocument() {
        // given：ACTIVE 且无解析中文档，CAS 置位命中，清退任务本次新建（非在飞）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(true);
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(true);

        // when
        KnowledgeBase accepted = service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：受理走单语句 CAS（不再整行 update），事务提交后投递清退任务并立即返回「删除中」快照
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        verify(knowledgeBaseRepository).transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING);
        verify(knowledgeBaseRepository, never()).update(any());
        verify(kbCleanupTaskSubmitter).submit(1L);
    }

    @Test
    void should_notCloseOutRow_when_delete_given_acceptanceSucceeded() {
        // given：受理矩阵只负责 CAS 置态与投递，收口（条件物理 DELETE）由清退线程经 Api→Repository 直连完成
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(true);
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(true);

        // when
        service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：受理侧零收口触达（知识库行必须留存至清退线程完成全部步骤）
        verify(knowledgeBaseRepository, never()).executeDelete(any());
    }

    @Test
    void should_throwConflictAndNotSubmit_when_delete_given_processingDocumentExists() {
        // given：ACTIVE 但存在解析中文档（拒绝受理）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(2L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete(new DeleteKnowledgeBaseCommand(1L)));
        assertTrue(ex.getMessage().contains("2"));
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
        verify(kbCleanupTaskSubmitter, never()).submit(any());
    }

    @Test
    void should_throwNotFoundAndNotSubmit_when_delete_given_missingKnowledgeBase() {
        // given：行不存在——含「清退已收口（条件物理 DELETE 后行消失）」，彻底物理删体系下与从未存在同形
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        // when // then：已删除库不可达即 404，不投递清退、不触任何状态写入
        assertThrows(ResourceNotFoundException.class, () -> service.delete(new DeleteKnowledgeBaseCommand(1L)));
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
        verify(knowledgeBaseRepository, never()).executeDelete(any());
        verify(kbCleanupTaskSubmitter, never()).submit(any());
    }

    @Test
    void should_casBackToDeletingAndClearTrace_when_delete_given_deleteFailedKnowledgeBase() {
        // given：DELETE_FAILED 重删——单语句 CAS 推回 DELETING 并同语句清除失败留痕
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETE_FAILED,
                        "[KB-CLEANUP] step=GRAPH: 数据库异常")));
        when(knowledgeBaseRepository.transitLifecycleClearingFailure(1L, LifecycleStatus.DELETE_FAILED,
                LifecycleStatus.DELETING)).thenReturn(true);
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(true);

        // when
        KnowledgeBase accepted = service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：受理成功回「删除中」且留痕已清；不做 PROCESSING 拦截（库已失活，无摄入竞态）
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        assertNull(accepted.errorMessage());
        verify(documentRepository, never()).countByKbId(any(), any(), any());
        verify(knowledgeBaseRepository).transitLifecycleClearingFailure(1L, LifecycleStatus.DELETE_FAILED,
                LifecycleStatus.DELETING);
        verify(kbCleanupTaskSubmitter).submit(1L);
    }

    @Test
    void should_throwConflictAndNotSubmit_when_delete_given_casMissOnDeleteFailed() {
        // given：读得 DELETE_FAILED 但 CAS 未命中（并发窗口下状态已被改走）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETE_FAILED, "上一轮留痕")));
        when(knowledgeBaseRepository.transitLifecycleClearingFailure(1L, LifecycleStatus.DELETE_FAILED,
                LifecycleStatus.DELETING)).thenReturn(false);

        // when // then：409 提示重发，且不投递
        assertThrows(ResourceConflictException.class, () -> service.delete(new DeleteKnowledgeBaseCommand(1L)));
        verify(kbCleanupTaskSubmitter, never()).submit(any());
    }

    @Test
    void should_returnDeletingIdempotently_when_delete_given_cleanupStillInFlight() {
        // given：DELETING 且清退任务在飞（提交端口返回 false 即去重命中，未产生第二个任务）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETING)));
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(false);

        // when
        KnowledgeBase accepted = service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：幂等回「删除中」——状态已是目标态故零 CAS，且 MUST NOT 产生第二个清退任务
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        verify(documentRepository, never()).countByKbId(any(), any(), any());
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
        verify(knowledgeBaseRepository, never()).transitLifecycleClearingFailure(any(), any(), any());
        verify(kbCleanupTaskSubmitter, times(1)).submit(1L);
    }

    @Test
    void should_retriggerCleanupTask_when_delete_given_deletingRowLeftByCrash() {
        // given：DELETING 但清退不在飞（提交端口返回 true 即本次新建任务——崩溃遗留续跑）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETING)));
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(true);

        // when
        KnowledgeBase accepted = service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：重触发成功受理，无需再动状态行
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        verify(knowledgeBaseRepository, never()).transitLifecycle(any(), any(), any());
        verify(kbCleanupTaskSubmitter).submit(1L);
    }

    @Test
    void should_throwConflictAndNotSubmit_when_delete_given_casMissOnActive() {
        // given：行锁下读得 ACTIVE 但 CAS 未命中（并发窗口下状态已被改走）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(false);

        // when // then：409 提示重发，且不投递
        assertThrows(ResourceConflictException.class, () -> service.delete(new DeleteKnowledgeBaseCommand(1L)));
        verify(kbCleanupTaskSubmitter, never()).submit(any());
    }

    @Test
    void should_acceptDeleteQuietly_when_delete_given_submitThrowsIllegalState() {
        // given：停机窗口投递拒收（IllegalStateException）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("服务正在停机"))
                .when(kbCleanupTaskSubmitter).submit(1L);

        // when // then：投递失败仅 ERROR 留痕，受理不回滚、不向用户抛出、不落任何失败态
        //（库停留 DELETING，由重删或启动一次性恢复兜底）
        KnowledgeBase accepted = assertDoesNotThrow(() -> service.delete(new DeleteKnowledgeBaseCommand(1L)));
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        verify(knowledgeBaseRepository).transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING);
        verify(knowledgeBaseRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_recreateWithSameName_when_create_given_previousRowPhysicallyClosed() {
        // given：前序清退链已经 Repository.executeDelete 条件物理 DELETE 收口（行消失 → uk_kb_name 释放，
        //       普通唯一索引无行冲突），故同名判重查询与主键查询均无命中
        when(knowledgeBaseRepository.findByName("产品手册")).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand("产品手册", "描述", "Chinese",
                null, null, null, null, null, null);

        // when：删库后同名立即重建（受理侧不再引入 DELETED 持久态）
        KnowledgeBase recreated = service.create(command);

        // then：重建成功且为全新 ACTIVE 库（无失败留痕）；已收口旧 id 直查 404（行不存在、无行可查）
        assertEquals(LifecycleStatus.ACTIVE, recreated.lifecycleStatus());
        assertNull(recreated.errorMessage());
        verify(knowledgeBaseRepository).save(any(KnowledgeBase.class));
        assertThrows(ResourceNotFoundException.class, () -> service.get(1L));
    }

    @Test
    void should_markFailedByCasWithReason_when_markFailed_given_deletingKnowledgeBase() {
        // given：DELETING → DELETE_FAILED 单语句 CAS 命中
        when(knowledgeBaseRepository.markFailed(1L, "[KB-CLEANUP] step=chunk_cleanup")).thenReturn(true);

        // when
        service.markFailed(1L, "[KB-CLEANUP] step=chunk_cleanup");

        // then：留痕原样透传（未超长不截断）
        verify(knowledgeBaseRepository).markFailed(1L, "[KB-CLEANUP] step=chunk_cleanup");
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void should_abbreviateReasonToLimit_when_markFailed_given_overLengthReason() {
        // given：超长失败原因（>500 字符）应截断后落库
        when(knowledgeBaseRepository.markFailed(eq(1L), any())).thenReturn(true);
        String longReason = "知".repeat(600);

        // when
        service.markFailed(1L, longReason);

        // then：落库文案不超过 500 字符截断上限
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeBaseRepository).markFailed(eq(1L), captor.capture());
        assertTrue(captor.getValue().length() <= 500);
        assertTrue(captor.getValue().startsWith("知"));
    }

    @Test
    void should_passNullReason_when_markFailed_given_nullReason() {
        // given：原因为空仅置态，CAS 未命中静默幂等
        when(knowledgeBaseRepository.markFailed(1L, null)).thenReturn(false);

        // when // then：不抛出、不覆盖并发链状态
        assertDoesNotThrow(() -> service.markFailed(1L, null));
        verify(knowledgeBaseRepository).markFailed(1L, null);
    }

    @Test
    void should_throwBadRequestAndSkipCas_when_markFailed_given_nullKnowledgeBaseId() {
        // when // then：kbId 为空直接 400，不触库
        assertThrows(DeepDataAgentException.class, () -> service.markFailed(null, "[KB-CLEANUP] step=x"));
        verify(knowledgeBaseRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_returnActiveKnowledgeBase_when_get_given_existingId() {
        // given
        KnowledgeBase existing = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(existing));

        // when
        KnowledgeBase result = service.get(1L);

        // then
        assertEquals(existing, result);
    }

    @Test
    void should_throwNotFound_when_get_given_missingId() {
        // given
        when(knowledgeBaseRepository.findById(404L)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get(404L));
    }

    @Test
    void should_passNullStatusForThreeStateVisibility_when_list_given_keywordQuery() {
        // given：列表可见性由仓储侧三态全集条件保证，应用层不下传具体状态值
        List<KnowledgeBase> pageData = List.of(
                buildKb(1L, "产品手册", LifecycleStatus.ACTIVE),
                buildKb(2L, "手册删除中", LifecycleStatus.DELETING),
                buildKb(3L, "手册删除失败", LifecycleStatus.DELETE_FAILED, "[KB-CLEANUP] step=GRAPH"));
        when(knowledgeBaseRepository.findByCondition(eq("手册"), isNull(),
                eq(KnowledgeBaseSortField.CREATED_AT), eq(false), eq(1), eq(20))).thenReturn(pageData);

        // when
        List<KnowledgeBase> result = service.list(new ListKnowledgeBaseQuery("手册", 1, 20, null, null));

        // then：三态全集（含删除中 / 删除失败库）一并回显（未提供排序参数时按创建时间倒序兜底）
        assertEquals(pageData, result);
        verify(knowledgeBaseRepository).findByCondition(eq("手册"), isNull(),
                eq(KnowledgeBaseSortField.CREATED_AT), eq(false), eq(1), eq(20));
    }

    @Test
    void should_passParsedSortParams_when_list_given_validSortOptions() {
        // given
        when(knowledgeBaseRepository.findByCondition(isNull(), isNull(),
                eq(KnowledgeBaseSortField.NAME), eq(true), eq(1), eq(20))).thenReturn(List.of());

        // when
        service.list(new ListKnowledgeBaseQuery(null, 1, 20, "name", "asc"));

        // then
        verify(knowledgeBaseRepository).findByCondition(isNull(), isNull(),
                eq(KnowledgeBaseSortField.NAME), eq(true), eq(1), eq(20));
    }

    @Test
    void should_throwBadRequest_when_list_given_unsupportedSortField() {
        // given
        ListKnowledgeBaseQuery query = new ListKnowledgeBaseQuery(null, 1, 20, "id", "asc");

        // when // then（白名单外排序字段直接拒绝而非静默忽略）
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.list(query));
        assertTrue(exception.getMessage().contains("不支持的排序字段"));
        verify(knowledgeBaseRepository, never()).findByCondition(any(), any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void should_throwBadRequest_when_list_given_unsupportedSortOrder() {
        // given
        ListKnowledgeBaseQuery query = new ListKnowledgeBaseQuery(null, 1, 20, "name", "descending");

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class, () -> service.list(query));
        assertTrue(exception.getMessage().contains("不支持的排序方向"));
    }

    @Test
    void should_returnZero_when_count_given_noMatch() {
        // given：计数口径与列表一致——不下传具体状态值，三态全集过滤在仓储侧完成
        when(knowledgeBaseRepository.countByCondition(isNull(), isNull())).thenReturn(0L);

        // when
        long total = service.count(null);

        // then
        assertEquals(0L, total);
        verify(knowledgeBaseRepository).countByCondition(isNull(), isNull());
    }

    @Test
    void should_returnThreeMetrics_when_stats_given_repositoriesReady() {
        // given
        when(knowledgeBaseRepository.countByStatus(LifecycleStatus.ACTIVE)).thenReturn(3L);
        when(documentRepository.countByKbId(null, null, null)).thenReturn(12L);
        when(chunkRepository.countByKbId(null, null, null, null)).thenReturn(300L);

        // when
        Map<String, Long> stats = service.stats();

        // then
        assertEquals(3, stats.size());
        assertEquals(3L, stats.get(KnowledgeBaseApplicationService.STATS_KEY_TOTAL_KNOWLEDGE_BASES).longValue());
        assertEquals(12L, stats.get(KnowledgeBaseApplicationService.STATS_KEY_TOTAL_DOCUMENTS).longValue());
        assertEquals(300L, stats.get(KnowledgeBaseApplicationService.STATS_KEY_TOTAL_CHUNKS).longValue());
    }

    @Test
    void should_onlyReplaceRetrievalStrategy_when_updateRetrievalConfig_given_validCommand() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateRetrievalConfigCommand command = new UpdateRetrievalConfigCommand(1L,
                "{\"strategyType\":\"naive\",\"resultChunkCount\":20}");

        // when
        KnowledgeBase updated = service.updateRetrievalConfig(command);

        // then（落库值为规范化后的 JSON：策略类型回写为枚举名）
        assertTrue(updated.retrievalStrategy().contains("\"strategyType\":\"NAIVE\""));
        assertTrue(updated.retrievalStrategy().contains("\"resultChunkCount\":20"));
        assertEquals(current.entityTypeConfig(), updated.entityTypeConfig());
    }

    @Test
    void should_throwBadRequest_when_updateRetrievalConfig_given_blankStrategy() {
        // given
        UpdateRetrievalConfigCommand command = new UpdateRetrievalConfigCommand(1L, " ");

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.updateRetrievalConfig(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_onlyReplaceEntityTypeConfig_when_updateEntityTypeConfig_given_validCommand() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateEntityTypeConfigCommand command = new UpdateEntityTypeConfigCommand(1L,
                "{\"entityTypes\":[{\"entityType\":\"Product\"}]}");

        // when
        KnowledgeBase updated = service.updateEntityTypeConfig(command);

        // then（实体类型校验仅拒绝不回写，合法配置以原文落库）
        assertEquals("{\"entityTypes\":[{\"entityType\":\"Product\"}]}", updated.entityTypeConfig());
        assertEquals(current.retrievalStrategy(), updated.retrievalStrategy());
    }

    @Test
    void should_throwNotFound_when_updateEntityTypeConfig_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        UpdateEntityTypeConfigCommand command = new UpdateEntityTypeConfigCommand(404L, "{\"entityTypes\":[]}");

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.updateEntityTypeConfig(command));
    }

    @Test
    void should_useDefaultPage_when_list_given_illegalPaging() {
        // given
        when(knowledgeBaseRepository.findByCondition(isNull(), isNull(),
                eq(KnowledgeBaseSortField.CREATED_AT), eq(false), eq(1), eq(20))).thenReturn(List.of());

        // when // then
        assertDoesNotThrow(() -> service.list(new ListKnowledgeBaseQuery(null, 0, -5, null, null)));
        verify(knowledgeBaseRepository).findByCondition(isNull(), isNull(),
                eq(KnowledgeBaseSortField.CREATED_AT), eq(false), eq(1), eq(20));
    }

    @Test
    void should_persistStrategyJsonAndReturnSnapshot_when_saveRetrievalConfig_given_validConfig() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        RetrievalConfigDTO config = new RetrievalConfigDTO("mix", Boolean.TRUE, 5, 0.6F, Boolean.TRUE,
                "{\"enabled\":true,\"modelProfileId\":\"rerank-unregistered-v1\",\"topK\":20}", null);

        // when
        RetrievalConfigDTO snapshot = service.saveRetrievalConfig(1L, config, 88L);

        // then（未注册模型标识仅做结构校验，可正常落库）
        assertEquals("MIX", snapshot.strategyType());
        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).update(captor.capture(), eq("88"));
        String strategyJson = captor.getValue().retrievalStrategy();
        assertTrue(strategyJson.contains("\"strategyType\":\"MIX\""));
        assertTrue(strategyJson.contains("\"resultChunkCount\":5"));
        assertTrue(strategyJson.contains("\"graphEnabled\":true"));
        assertTrue(strategyJson.contains("\"modelProfileId\":\"rerank-unregistered-v1\""));
        assertEquals(current.entityTypeConfig(), captor.getValue().entityTypeConfig());
    }

    @Test
    void should_defaultGraphDisabled_when_saveRetrievalConfig_given_graphEnabledNull() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        RetrievalConfigDTO config = new RetrievalConfigDTO("NAIVE", null, null, null, null, null, null);

        // when
        service.saveRetrievalConfig(1L, config, null);

        // then
        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).update(captor.capture(), isNull());
        assertTrue(captor.getValue().retrievalStrategy().contains("\"graphEnabled\":false"));
        assertFalse(captor.getValue().retrievalStrategy().contains("resultChunkCount"));
    }

    @Test
    void should_throwBadRequest_when_saveRetrievalConfig_given_unknownStrategyType() {
        // given
        RetrievalConfigDTO config = new RetrievalConfigDTO("HYBRID", null, null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.saveRetrievalConfig(1L, config, 1L));
        verify(knowledgeBaseRepository, never()).update(any(), any());
    }

    @Test
    void should_throwBadRequest_when_saveRetrievalConfig_given_illegalRerankJson() {
        // given
        RetrievalConfigDTO config = new RetrievalConfigDTO("MIX", null, null, null, null, "{invalid", null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.saveRetrievalConfig(1L, config, 1L));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any(), any());
    }

    @Test
    void should_throwNotFound_when_saveRetrievalConfig_given_knowledgeBaseNotExist() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        RetrievalConfigDTO config = new RetrievalConfigDTO("MIX", null, null, null, null, null, null);

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.saveRetrievalConfig(404L, config, 1L));
        verify(knowledgeBaseRepository, never()).update(any(), any());
    }

    @Test
    void should_throwBadRequest_when_saveRetrievalConfig_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.DELETING)));
        RetrievalConfigDTO config = new RetrievalConfigDTO("MIX", null, null, null, null, null, null);

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.saveRetrievalConfig(1L, config, 1L));
        verify(knowledgeBaseRepository, never()).update(any(), any());
    }

    @Test
    void should_throwBadRequest_when_saveRetrievalConfig_given_nullKnowledgeBaseId() {
        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> service.saveRetrievalConfig(null, new RetrievalConfigDTO("MIX", null, null, null,
                        null, null, null), 1L));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void should_writeBackDefaultRrfK_when_updateRetrievalConfig_given_rrfWithoutK() {
        // given
        KnowledgeBase current = buildKb(1L, "产品手册", LifecycleStatus.ACTIVE);
        when(knowledgeBaseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        when(knowledgeBaseRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateRetrievalConfigCommand command = new UpdateRetrievalConfigCommand(1L,
                "{\"strategyType\":\"naive\",\"fusionConfig\":{\"fusionType\":\"rrf\"}}");

        // when
        KnowledgeBase updated = service.updateRetrievalConfig(command);

        // then（REST 入口同样回写缺省 rrfK=60 与规范化融合类型）
        assertTrue(updated.retrievalStrategy().contains("\"fusionType\":\"RRF\""));
        assertTrue(updated.retrievalStrategy().contains("\"rrfK\":60"));
    }

    @Test
    void should_throwBadRequest_when_updateRetrievalConfig_given_denseWeightOutOfRange() {
        // given：稠密权重 1.0 越界，校验前置于事务，不开库不读行
        UpdateRetrievalConfigCommand command = new UpdateRetrievalConfigCommand(1L,
                "{\"strategyType\":\"mix\",\"fusionConfig\":{\"fusionType\":\"WEIGHTED_SUM\","
                        + "\"channelDenseWeight\":{\"VECTOR\":1.0}}}");

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.updateRetrievalConfig(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequest_when_saveRetrievalConfig_given_denseWeightOutOfRange() {
        // given：契约入口与 REST 入口共用同一套校验
        RetrievalConfigDTO config = new RetrievalConfigDTO("MIX", null, null, null, null, null,
                "{\"fusionType\":\"WEIGHTED_SUM\",\"channelDenseWeight\":{\"VECTOR\":1.0}}");

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.saveRetrievalConfig(1L, config, 1L));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any(), any());
    }

    @Test
    void should_returnNormalizedFusionSnapshot_when_saveRetrievalConfig_given_rrfWithoutK() {
        // given
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        RetrievalConfigDTO config = new RetrievalConfigDTO("naive", null, null, null, null, null,
                "{\"fusionType\":\"rrf\"}");

        // when
        RetrievalConfigDTO snapshot = service.saveRetrievalConfig(1L, config, null);

        // then（生效配置 rrfK 为 60：落库 JSON 与返回快照均含回写值）
        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).update(captor.capture(), isNull());
        assertTrue(captor.getValue().retrievalStrategy().contains("\"rrfK\":60"));
        assertNotNull(snapshot.fusionConfig());
        assertTrue(snapshot.fusionConfig().contains("\"rrfK\":60"));
        assertTrue(snapshot.fusionConfig().contains("\"fusionType\":\"RRF\""));
    }

    @Test
    void should_throwBadRequest_when_updateEntityTypeConfig_given_illegalName() {
        // given：名称含非法字符，校验前置于事务，配置不落库
        UpdateEntityTypeConfigCommand command = new UpdateEntityTypeConfigCommand(1L,
                "{\"entityTypes\":[{\"entityType\":\"用户@A\"}]}");

        // when // then
        assertThrows(DeepDataAgentException.class, () -> service.updateEntityTypeConfig(command));
        verify(knowledgeBaseRepository, never()).findByIdForUpdate(any());
        verify(knowledgeBaseRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequest_when_updateEntityTypeConfig_given_builtinName() {
        // given：撞内置类型名「组织」
        UpdateEntityTypeConfigCommand command = new UpdateEntityTypeConfigCommand(1L,
                "{\"entityTypes\":[{\"entityType\":\"组织\"}]}");

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> service.updateEntityTypeConfig(command));
        assertTrue(exception.getMessage().contains("内置类型"));
        verify(knowledgeBaseRepository, never()).update(any());
    }

    // ==================== 在飞任务登记与状态一致性收敛 ====================

    /** 在飞登记失败的失败留痕文案（与生产常量 KB_CLEANUP_REGISTER_FAILED_TRACE 同值） */
    private static final String REGISTER_FAILED_TRACE = "[KB-CLEANUP] step=in_flight_register: 在飞登记失败";

    @Test
    void should_registerBeforeSubmit_when_submitCleanupTaskAfterCommit_given_activeKnowledgeBase() {
        // given：ACTIVE 且无解析中文档，CAS 置位命中，清退任务本次新建（非在飞）
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(true);
        when(kbCleanupTaskSubmitter.submit(1L)).thenReturn(true);

        // when
        service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：先在飞登记、后投递清退任务——登记先于投递，投递失败也留有残留凭据
        InOrder inOrder = inOrder(inFlightTaskRegistry, kbCleanupTaskSubmitter);
        inOrder.verify(inFlightTaskRegistry).register(InFlightTaskType.KB_CLEANUP, 1L);
        inOrder.verify(kbCleanupTaskSubmitter).submit(1L);
    }

    @Test
    void should_markFailedWithoutSubmit_when_submitCleanupTaskAfterCommit_given_registerThrows() {
        // given：在飞注册表不可用（登记抛异常），受理事务已提交
        when(knowledgeBaseRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(buildKb(1L, "产品手册", LifecycleStatus.ACTIVE)));
        when(documentRepository.countByKbId(1L, null, DocumentStatus.PROCESSING)).thenReturn(0L);
        when(knowledgeBaseRepository.transitLifecycle(1L, LifecycleStatus.ACTIVE, LifecycleStatus.DELETING))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("在飞注册表不可用"))
                .when(inFlightTaskRegistry).register(InFlightTaskType.KB_CLEANUP, 1L);
        when(knowledgeBaseRepository.markFailed(1L, REGISTER_FAILED_TRACE)).thenReturn(true);

        // when
        KnowledgeBase accepted = service.delete(new DeleteKnowledgeBaseCommand(1L));

        // then：登记失败 MUST NOT 静默放行——不投递清退任务，按失败兜底置 DELETE_FAILED 并留痕
        assertEquals(LifecycleStatus.DELETING, accepted.lifecycleStatus());
        verify(kbCleanupTaskSubmitter, never()).submit(any());
        verify(knowledgeBaseRepository).markFailed(1L, REGISTER_FAILED_TRACE);
    }
}

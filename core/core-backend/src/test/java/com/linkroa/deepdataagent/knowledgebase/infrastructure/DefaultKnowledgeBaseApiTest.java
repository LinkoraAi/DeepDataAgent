package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.contract.KnowledgeBaseReferenceDTO;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultKnowledgeBaseApi} 知识库服务契约单测：读侧 mock 仓储，写侧全部直连领域仓储
 * （删除收口 executeDelete 为条件物理 DELETE；失败留痕 markFailed 为单语句 CAS）。
 * <p>读侧覆盖：引用解析与 ACTIVE 判定、检索策略 / 语言 / 模型 profileId 三段只读投影
 * （语言真相源为 {@code knowledge_base.language} 列，读取侧归一 + 值域外原样透传；
 * {@code multi_model_config} 的知识库级通用 LLM 引用与 {@code embedding_config} 的嵌入模型引用，
 * 含 kbId 为空不触库、列空白、键缺失 / 值空白按未配置返回 null、非法 JSON 视为数据损坏）、
 * 清退恢复扫描（findIdsByLifecycle）、切片批量回取与写侧委托。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultKnowledgeBaseApiTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @InjectMocks
    private DefaultKnowledgeBaseApi api;

    private KnowledgeBase buildKb(LifecycleStatus status) {
        return buildKb(status, null);
    }

    private KnowledgeBase buildKb(LifecycleStatus status, String retrievalStrategy) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", status,
                null, null, null, retrievalStrategy, null, null, null, now, now);
    }

    /**
     * 构造仅携带 language 列值的知识库聚合根（语言只读投影用例，真相源 = language 列）。
     */
    private KnowledgeBase buildKbWithLanguage(String language) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", language, LifecycleStatus.ACTIVE,
                null, null, null, null, null, null, null, now, now);
    }

    /**
     * 构造仅携带 multi_model_config 的知识库聚合根（知识库级通用 LLM 模型 profileId 只读投影用例）。
     */
    private KnowledgeBase buildKbWithMultiModelConfig(String multiModelConfig) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.ACTIVE,
                null, null, null, null, null, multiModelConfig, null, now, now);
    }

    /**
     * 构造仅携带 embedding_config 的知识库聚合根（嵌入模型 profileId 只读投影用例）。
     */
    private KnowledgeBase buildKbWithEmbeddingConfig(String embeddingConfig) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.ACTIVE,
                null, null, null, null, embeddingConfig, null, null, now, now);
    }

    @Test
    void should_returnReference_when_resolveById_given_existingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when
        Optional<KnowledgeBaseReferenceDTO> reference = api.resolveById(KB_ID);

        // then
        assertTrue(reference.isPresent());
        assertEquals(KB_ID, reference.get().kbId());
        assertEquals("产品手册", reference.get().name());
        assertEquals("ACTIVE", reference.get().lifecycleStatus());
    }

    @Test
    void should_returnEmpty_when_resolveById_given_nullId() {
        // when
        Optional<KnowledgeBaseReferenceDTO> reference = api.resolveById(null);

        // then
        assertFalse(reference.isPresent());
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnEmpty_when_resolveById_given_missingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertFalse(api.resolveById(KB_ID).isPresent());
    }

    @Test
    void should_returnTrue_when_isActive_given_activeKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE)));

        // when // then
        assertTrue(api.isActive(KB_ID));
    }

    @Test
    void should_returnFalse_when_isActive_given_deletingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.DELETING)));

        // when // then
        assertFalse(api.isActive(KB_ID));
    }

    @Test
    void should_returnFalse_when_isActive_given_missingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertFalse(api.isActive(KB_ID));
    }

    @Test
    void should_returnFalse_when_isActive_given_nullId() {
        // when // then
        assertFalse(api.isActive(null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnTrue_when_executeDelete_given_conditionDeleteHit() {
        // given：仓储收口条件物理 DELETE 命中
        when(knowledgeBaseRepository.executeDelete(KB_ID)).thenReturn(true);

        // when
        boolean deleted = api.executeDelete(KB_ID);

        // then：直连本 BC 领域仓储完成收口（executeDelete 由 Repository 提供）
        assertTrue(deleted);
        verify(knowledgeBaseRepository).executeDelete(KB_ID);
    }

    @Test
    void should_returnFalseQuietly_when_executeDelete_given_zeroRowMiss() {
        // given：0 行命中（已收口 / 状态不符）幂等空转，不抛出
        when(knowledgeBaseRepository.executeDelete(KB_ID)).thenReturn(false);

        // when // then
        assertFalse(api.executeDelete(KB_ID));
        verify(knowledgeBaseRepository).executeDelete(KB_ID);
    }

    @Test
    void should_throwBadRequestAndSkipDelete_when_executeDelete_given_nullKnowledgeBaseId() {
        // when // then：kbId 为空 → 400 参数异常，仓储零交互
        assertThrows(DeepDataAgentException.class, () -> api.executeDelete(null));
        verify(knowledgeBaseRepository, never()).executeDelete(any());
    }

    @Test
    void should_markFailedViaRepository_when_markFailed_given_deletingKnowledgeBase() {
        // given：DELETING → DELETE_FAILED 的 CAS 命中
        when(knowledgeBaseRepository.markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup")).thenReturn(true);

        // when
        api.markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup");

        // then：直连领域仓储完成条件置态与失败留痕
        verify(knowledgeBaseRepository).markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup");
    }

    @Test
    void should_swallowQuietly_when_markFailed_given_zeroRowMiss() {
        // given：非 DELETING 源态零行命中（幂等空转，MUST NOT 覆盖并发链已写入的状态）
        when(knowledgeBaseRepository.markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup")).thenReturn(false);

        // when // then：不抛出，留痕按幂等空转处理
        api.markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup");
        verify(knowledgeBaseRepository).markFailed(KB_ID, "[KB-CLEANUP] step=chunk_cleanup");
    }

    @Test
    void should_throwBadRequestAndSkipWrite_when_markFailed_given_nullKnowledgeBaseId() {
        // when // then：kbId 为空 → 400 参数异常，仓储零写入
        assertThrows(DeepDataAgentException.class, () -> api.markFailed(null, "[KB-CLEANUP] step=chunk_cleanup"));
        verify(knowledgeBaseRepository, never()).markFailed(any(), any());
    }

    @Test
    void should_returnConfig_when_findRetrievalStrategyByKbId_given_validMixedStrategy() {
        // given
        String strategyJson = "{\"strategyType\":\"MIX\",\"rewriteQuestion\":true,"
                + "\"resultChunkCount\":5,\"similarThreshold\":0.65,\"graphEnabled\":true,"
                + "\"rerankConfig\":{\"enabled\":true,\"modelProfileId\":\"rerank-1\",\"topK\":3},"
                + "\"fusionConfig\":{\"fusionType\":\"RRF\",\"rrfK\":80}}";
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, strategyJson)));

        // when
        RetrievalStrategyConfig config = api.findRetrievalStrategyByKbId(KB_ID);

        // then：JSON 全量映射到领域值对象
        assertEquals(RetrievalStrategyType.MIX, config.strategyType());
        assertEquals(Boolean.TRUE, config.rewriteQuestion());
        assertEquals(5, config.resultChunkCount());
        assertEquals(0.65F, config.similarThreshold(), 0.001F);
        assertTrue(config.rerankConfig().isEnabled());
        assertEquals("rerank-1", config.rerankConfig().modelProfileId());
        assertEquals(3, config.rerankConfig().topK());
        assertEquals(FusionStrategyType.RRF, config.fusionConfig().fusionType());
        assertEquals(80, config.fusionConfig().rrfK());
        assertNull(config.fusionConfig().channelDenseWeight());
    }

    @Test
    void should_returnWeightedSumFusion_when_findRetrievalStrategyByKbId_given_weightedSumStrategy() {
        // given：可选字段缺失按 null 兜底；WEIGHTED_SUM 读取通道稠密权重
        String strategyJson = "{\"strategyType\":\"NAIVE\","
                + "\"fusionConfig\":{\"fusionType\":\"WEIGHTED_SUM\","
                + "\"channelDenseWeight\":{\"VECTOR\":0.5,\"BM25\":0.5}}}";
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, strategyJson)));

        // when
        RetrievalStrategyConfig config = api.findRetrievalStrategyByKbId(KB_ID);

        // then
        assertEquals(RetrievalStrategyType.NAIVE, config.strategyType());
        assertNull(config.rewriteQuestion());
        assertNull(config.rerankConfig());
        assertEquals(FusionStrategyType.WEIGHTED_SUM, config.fusionConfig().fusionType());
        assertEquals(2, config.fusionConfig().channelDenseWeight().size());
        assertEquals(0.5F, config.fusionConfig().channelDenseWeight().get(RetrievalChannel.VECTOR), 0.001F);
        assertEquals(0.5F, config.fusionConfig().channelDenseWeight().get(RetrievalChannel.BM25), 0.001F);
    }

    @Test
    void should_returnNull_when_findRetrievalStrategyByKbId_given_nullId() {
        // when // then
        assertNull(api.findRetrievalStrategyByKbId(null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnNull_when_findRetrievalStrategyByKbId_given_missingKnowledgeBase() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertNull(api.findRetrievalStrategyByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findRetrievalStrategyByKbId_given_blankStrategy() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, "  ")));

        // when // then
        assertNull(api.findRetrievalStrategyByKbId(KB_ID));
    }

    @Test
    void should_throwDataCorruption_when_findRetrievalStrategyByKbId_given_illegalJson() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, "{not-json")));

        // when // then：库中 JSON 非法视为数据损坏
        assertThrows(DeepDataAgentException.class, () -> api.findRetrievalStrategyByKbId(KB_ID));
    }

    @Test
    void should_throwDataCorruption_when_findRetrievalStrategyByKbId_given_missingStrategyType() {
        // given：策略类型缺失触发领域 record 不变量校验
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKb(LifecycleStatus.ACTIVE, "{\"rewriteQuestion\":true}")));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> api.findRetrievalStrategyByKbId(KB_ID));
    }

    @Test
    void should_returnNormalizedLanguage_when_findLanguageByKbId_given_paddedFullName() {
        // given：language 列值带首尾空白的小写语言全名（读取侧 trim + 大小写不敏感归一）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithLanguage(" japanese ")));

        // when // then：归一为枚举规范全名
        assertEquals("Japanese", api.findLanguageByKbId(KB_ID));
    }

    @Test
    void should_normalizeToEnumFullName_when_findLanguageByKbId_given_legacyUppercaseSample() {
        // given：V16 回填保留的存量大小写变体样本（如历史缺省值 ENGLISH）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithLanguage("ENGLISH")));

        // when // then：读取侧归一为 English（列不重写值域样本）
        assertEquals("English", api.findLanguageByKbId(KB_ID));
    }

    @Test
    void should_passThroughBareCode_when_findLanguageByKbId_given_legacyBareLanguageCode() {
        // given：历史裸语言码宽容透传（原样 trim，由下游模板套归一按 en 套处理）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithLanguage(" ja ")));

        // when // then
        assertEquals("ja", api.findLanguageByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findLanguageByKbId_given_nullKbId() {
        // given：kbId 为空不触库
        // when // then
        assertNull(api.findLanguageByKbId(null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnNull_when_findLanguageByKbId_given_blankLanguageColumn() {
        // given：language 列空白按未配置处理（缺省回落由调用方负责）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKbWithLanguage("   ")));

        // when // then
        assertNull(api.findLanguageByKbId(KB_ID));

        // given：language 列为 null（历史行未经 V16 回填）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKbWithLanguage(null)));

        // when // then
        assertNull(api.findLanguageByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findLanguageByKbId_given_knowledgeBaseMissing() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertNull(api.findLanguageByKbId(KB_ID));
    }

    @Test
    void should_delegateToRepository_when_findIdsByLifecycle_given_deletingStatus() {
        // given：清退恢复扫描场景
        when(knowledgeBaseRepository.findIdsByLifecycle(LifecycleStatus.DELETING))
                .thenReturn(List.of(1L, 5L, 9L));

        // when
        List<Long> ids = api.findIdsByLifecycle(LifecycleStatus.DELETING);

        // then：委托仓储返回升序主键列表（逻辑删除行排除由持久层保证）
        assertEquals(List.of(1L, 5L, 9L), ids);
        verify(knowledgeBaseRepository).findIdsByLifecycle(LifecycleStatus.DELETING);
    }

    @Test
    void should_returnEmptyWithoutTouchingRepository_when_findIdsByLifecycle_given_nullLifecycle() {
        // when // then：空入参零存储交互
        assertTrue(api.findIdsByLifecycle(null).isEmpty());
        verify(knowledgeBaseRepository, never()).findIdsByLifecycle(any());
    }

    @Test
    void should_returnTrimmedProfileId_when_findMediaModelProfileIdByKbId_given_multiModelConfigured() {
        // given：profileId 带首尾空白（读侧 trim 归一）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithMultiModelConfig("{\"modelProfileId\":\"  vlm-profile-1  \"}")));

        // when // then
        assertEquals("vlm-profile-1", api.findMediaModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findMediaModelProfileIdByKbId_given_nullKbId() {
        // when // then：空 kbId 不触库
        assertNull(api.findMediaModelProfileIdByKbId(null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnNull_when_findMediaModelProfileIdByKbId_given_configNotConfigured() {
        // given：多模态配置列空白
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithMultiModelConfig("  ")));

        // when // then
        assertNull(api.findMediaModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findMediaModelProfileIdByKbId_given_profileIdKeyMissingOrBlank() {
        // given：合法 JSON 但 modelProfileId 键缺失 / 值为空白，一律按「未配置视觉模型」处理
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithMultiModelConfig("{\"enableOcr\":true}")));

        // when // then
        assertNull(api.findMediaModelProfileIdByKbId(KB_ID));

        // given：值为空白串
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithMultiModelConfig("{\"modelProfileId\":\"   \"}")));

        // when // then
        assertNull(api.findMediaModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findMediaModelProfileIdByKbId_given_knowledgeBaseMissing() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertNull(api.findMediaModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_throwDataCorruption_when_findMediaModelProfileIdByKbId_given_illegalJson() {
        // given：库中多模态配置非法 JSON 视为数据损坏（与语言/策略读取口径一致）
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithMultiModelConfig("{not-json")));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> api.findMediaModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnTrimmedProfileId_when_findEmbeddingModelProfileIdByKbId_given_embeddingConfigured() {
        // given：profileId 带首尾空白（读侧 trim 归一，与摄入侧同源列）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithEmbeddingConfig("{\"modelProfileId\":\"  embed-profile-1  \"}")));

        // when // then
        assertEquals("embed-profile-1", api.findEmbeddingModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findEmbeddingModelProfileIdByKbId_given_nullKbId() {
        // when // then：空 kbId 不触库
        assertNull(api.findEmbeddingModelProfileIdByKbId(null));
        verify(knowledgeBaseRepository, never()).findById(any());
    }

    @Test
    void should_returnNull_when_findEmbeddingModelProfileIdByKbId_given_profileIdKeyMissingOrBlank() {
        // given & then：键缺失 / 值空白 / 整段空白一律按「未配置嵌入模型」返回 null，不回退任何默认值
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithEmbeddingConfig("{\"dimension\":1024}")));
        assertNull(api.findEmbeddingModelProfileIdByKbId(KB_ID));

        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithEmbeddingConfig("{\"modelProfileId\":\"   \"}")));
        assertNull(api.findEmbeddingModelProfileIdByKbId(KB_ID));

        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(buildKbWithEmbeddingConfig("  ")));
        assertNull(api.findEmbeddingModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findEmbeddingModelProfileIdByKbId_given_knowledgeBaseMissing() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.empty());

        // when // then
        assertNull(api.findEmbeddingModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_throwDataCorruption_when_findEmbeddingModelProfileIdByKbId_given_illegalJson() {
        // given
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithEmbeddingConfig("{not-json")));

        // when // then
        assertThrows(DeepDataAgentException.class, () -> api.findEmbeddingModelProfileIdByKbId(KB_ID));
    }

    @Test
    void should_delegateToRepository_when_findChunksByKbIdAndChunkIds_given_validIds() {
        // given
        Chunk chunk = Chunk.restore(300L, KB_ID, 11L, 2, 64, "检索命中正文", null,
                ChunkContentType.TEXT, "手册.pdf", null, ChunkSource.PARSED,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(chunkRepository.findByKbIdAndIds(KB_ID, List.of(300L))).thenReturn(List.of(chunk));

        // when
        List<Chunk> chunks = api.findChunksByKbIdAndChunkIds(KB_ID, List.of(300L));

        // then：委托切片仓储批量回取，返回完整领域模型
        assertEquals(1, chunks.size());
        assertEquals(300L, chunks.get(0).id());
        assertEquals("检索命中正文", chunks.get(0).chunkContent());
        verify(chunkRepository).findByKbIdAndIds(KB_ID, List.of(300L));
    }

    @Test
    void should_returnEmpty_when_findChunksByKbIdAndChunkIds_given_nullKbId() {
        // when
        List<Chunk> chunks = api.findChunksByKbIdAndChunkIds(null, List.of(300L));

        // then
        assertTrue(chunks.isEmpty());
        verify(chunkRepository, never()).findByKbIdAndIds(any(), any());
    }

    @Test
    void should_returnEmpty_when_findChunksByKbIdAndChunkIds_given_emptyChunkIds() {
        // when
        List<Chunk> chunks = api.findChunksByKbIdAndChunkIds(KB_ID, List.of());

        // then
        assertTrue(chunks.isEmpty());
        verify(chunkRepository, never()).findByKbIdAndIds(any(), any());
    }

    /**
     * 构造仅携带 entity_type_config / rag_engine_config 的知识库聚合根（组 6 删除期只读取数用例）。
     */
    private KnowledgeBase buildKbWithConfigs(String ragEngineConfig, String entityTypeConfig) {
        OffsetDateTime now = OffsetDateTime.now();
        return KnowledgeBase.restore(KB_ID, "产品手册", "描述", "Chinese", LifecycleStatus.ACTIVE,
                null, ragEngineConfig, null, null, null, null, entityTypeConfig, now, now);
    }

    @Test
    void should_returnEntityTypes_when_findEntityTypesByKbId_given_objectArrayConfig() {
        // given：标准对象数组形态（与摄入端 DefaultIngestionSourceReader 解析同口径）
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithConfigs(null, "{\"entityTypes\":[{\"entityType\":\"Person\"},{\"entityType\":\"Org\"}]}")));

        // when
        List<EntityType> types = api.findEntityTypesByKbId(KB_ID);

        // then
        assertEquals(List.of(new EntityType("Person"), new EntityType("Org")), types);
    }

    @Test
    void should_returnEntityTypes_when_findEntityTypesByKbId_given_stringArrayLegacyShape() {
        // given：兼容纯字符串数组形态
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithConfigs(null, "{\"entityTypes\":[\"Person\"]}")));

        // when // then
        assertEquals(List.of(new EntityType("Person")), api.findEntityTypesByKbId(KB_ID));
    }

    @Test
    void should_returnEmptyListQuietly_when_findEntityTypesByKbId_given_illegalOrMissingConfig() {
        // given & then：非法 JSON / 非数组 / 列空白 / 库不存在 / kbId 为空——非关键路径按空清单兜底，不抛出
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithConfigs(null, "{not-json")));
        assertTrue(api.findEntityTypesByKbId(KB_ID).isEmpty());

        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithConfigs(null, "{\"entityTypes\":\"oops\"}")));
        assertTrue(api.findEntityTypesByKbId(KB_ID).isEmpty());

        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithConfigs(null, "  ")));
        assertTrue(api.findEntityTypesByKbId(KB_ID).isEmpty());

        // given & then：kbId 为空直接空清单（不触库）
        assertTrue(api.findEntityTypesByKbId(null).isEmpty());
    }

    @Test
    void should_returnRawTrimmedJson_when_findRagEngineConfigJsonByKbId_given_configured() {
        // given：无损只读投影——原文 trim 透传，提供方不解析子段
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(
                buildKbWithConfigs("  {\"graphMerge\":{\"apply_source_ids_limit\":300}}  ", null)));

        // when // then
        assertEquals("{\"graphMerge\":{\"apply_source_ids_limit\":300}}",
                api.findRagEngineConfigJsonByKbId(KB_ID));
    }

    @Test
    void should_returnNull_when_findRagEngineConfigJsonByKbId_given_blankOrMissing() {
        // given：列空白按未配置处理
        when(knowledgeBaseRepository.findById(KB_ID))
                .thenReturn(Optional.of(buildKbWithConfigs("   ", null)));

        // when // then
        assertNull(api.findRagEngineConfigJsonByKbId(KB_ID));

        // given：kbId 为空不触库
        // when // then
        assertNull(api.findRagEngineConfigJsonByKbId(null));
        verify(knowledgeBaseRepository, times(1)).findById(KB_ID);
    }
}

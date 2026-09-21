package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.infrastructure.client.RerankClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultReranker} 单元测试（Stage 3 精排）。
 *
 * <p>{@link RerankClient} 与 {@link KnowledgeBaseApi} 全部 Mockito 模拟，不启动 Spring 容器、
 * 不触碰数据库与远程 reranker。</p>
 *
 * <p>覆盖场景：未启用（含策略配置/重排配置缺失）直出且不产生任何依赖交互；topK 截断；
 * <b>多候选只发生一次批量打分调用</b>；重排分降序（同分保持粗排相对次序）+ similarThreshold 过滤
 * + resultChunkCount 截断；批量打分异常/返回对齐违约降级直出粗排；chunk 正文缺失候选未打分置于尾部；
 * 全部候选无正文时不发起打分调用；kbId 与 chunkId 集合正确传递到正文回取；空候选边界。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultRerankerTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 测试用文档ID（构造 Chunk 领域模型必填） */
    private static final Long DOCUMENT_ID = 11L;

    /** 测试用用户查询 */
    private static final String QUERY = "重排模型怎么配置";

    /** 重排模型 profileId */
    private static final String RERANK_PROFILE = "model-rerank";

    /** 候选1正文 */
    private static final String PASSAGE_1 = "正文一";

    /** 候选2正文 */
    private static final String PASSAGE_2 = "正文二";

    /** 候选3正文 */
    private static final String PASSAGE_3 = "正文三";

    /** 候选4正文（topK 截断后不应被打分） */
    private static final String PASSAGE_4 = "正文四";

    /** 断言浮点分数误差容忍度 */
    private static final double DELTA = 1E-9;

    /** 重排打分端口桩 */
    @Mock
    private RerankClient rerankClient;

    /** 知识库只读消费面桩 */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 被测精排服务（Mockito 按类型走全参构造器注入） */
    @InjectMocks
    private DefaultReranker reranker;

    /**
     * 构造粗排候选。
     *
     * @param chunkId chunk 标识
     * @param score   粗排分
     * @return 候选值对象
     */
    private static RankedChunk coarse(long chunkId, double score) {
        return new RankedChunk(chunkId, score, "来源-" + chunkId + ".pdf");
    }

    /**
     * 构造可回取正文的切片领域模型。
     *
     * @param chunkId chunk 标识
     * @param content 切片正文
     * @return 切片
     */
    private static Chunk chunkWithContent(long chunkId, String content) {
        OffsetDateTime now = OffsetDateTime.now();
        return Chunk.restore(chunkId, KB_ID, DOCUMENT_ID, 1, 64, content, null,
                ChunkContentType.TEXT, "来源-" + chunkId + ".pdf", null, ChunkSource.PARSED, now, now);
    }

    /**
     * 构造检索策略配置。
     *
     * @param topK             重排 top K（为空回退默认 50）
     * @param similarThreshold 相似度阈值，可为空
     * @param resultChunkCount 结果返回数量，可为空
     * @return 启用重排的策略配置
     */
    private static RetrievalStrategyConfig enabledStrategy(Integer topK, Float similarThreshold,
                                                           Integer resultChunkCount) {
        RerankModelConfig rerankConfig = new RerankModelConfig(Boolean.TRUE, RERANK_PROFILE, topK);
        return new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE, Boolean.FALSE,
                resultChunkCount, similarThreshold, rerankConfig, null);
    }

    /**
     * 构造未启用重排的策略配置。
     *
     * @return 策略配置（rerankConfig.enabled=false）
     */
    private static RetrievalStrategyConfig disabledStrategy() {
        RerankModelConfig rerankConfig = new RerankModelConfig(Boolean.FALSE, RERANK_PROFILE, 50);
        return new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE, Boolean.FALSE,
                5, 0.3F, rerankConfig, null);
    }

    /**
     * 场景①-1：重排未启用 → 直出粗排且不产生任何依赖交互。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_rerankDisabled() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.5D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, disabledStrategy(), KB_ID);

        // then
        assertSame(candidates, result);
        verifyNoInteractions(rerankClient, knowledgeBaseApi);
    }

    /**
     * 场景①-2：策略配置整体缺失 → 直出粗排且不产生任何依赖交互。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_nullStrategyConfig() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.5D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, null, KB_ID);

        // then
        assertSame(candidates, result);
        verifyNoInteractions(rerankClient, knowledgeBaseApi);
    }

    /**
     * 场景①-3：策略配置存在但重排子配置缺失 → 直出粗排（边界）。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_missingRerankConfig() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D));
        RetrievalStrategyConfig cfg = new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE,
                Boolean.FALSE, 5, 0.3F, null, null);

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertSame(candidates, result);
        verifyNoInteractions(rerankClient, knowledgeBaseApi);
    }

    /**
     * 场景①-4：候选为空 → 返回空列表（边界，不触发正文回取与打分）。
     */
    @Test
    void should_returnEmptyList_when_rerank_given_emptyCandidates() {
        // given
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, List.of(), cfg, KB_ID);

        // then
        assertTrue(result.isEmpty());
        verifyNoInteractions(rerankClient, knowledgeBaseApi);
    }

    /**
     * 场景②-1（核心）：多候选精排只发生一次批量打分调用，且该次调用携带全部候选正文。
     */
    @Test
    void should_invokeRerankClientOnce_when_rerank_given_multipleScoreableCandidates() {
        // given：3 个候选全部可打分
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D), coarse(3L, 0.7D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2),
                        chunkWithContent(3L, PASSAGE_3)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2, PASSAGE_3)))
                .thenReturn(List.of(0.3D, 0.6D, 0.9D));

        // when
        reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：批量打分仅一次，且该次入参覆盖全部三个候选正文（顺序为粗排顺序）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> passagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(rerankClient, times(1)).scoreBatch(eq(RERANK_PROFILE), eq(QUERY), passagesCaptor.capture());
        assertEquals(List.of(PASSAGE_1, PASSAGE_2, PASSAGE_3), passagesCaptor.getValue());
    }

    /**
     * 场景②-2：topK 截断（超出部分不进入批量打分）+ 重排分降序重排。
     */
    @Test
    void should_truncateByTopKAndSortDesc_when_rerank_given_topKSmallerThanCandidates() {
        // given：粗排 1→2→3，topK=2 只允许前两个进入打分；重排后 2 分最高
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D), coarse(3L, 0.7D));
        RetrievalStrategyConfig cfg = enabledStrategy(2, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2),
                        chunkWithContent(3L, PASSAGE_3)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(List.of(0.2D, 0.9D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：仅两个候选进入打分，按重排分降序为 2→1
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).chunkId());
        assertEquals(0.9D, result.get(0).score(), DELTA);
        assertEquals(1L, result.get(1).chunkId());
        assertEquals(0.2D, result.get(1).score(), DELTA);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> passagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(rerankClient, times(1)).scoreBatch(eq(RERANK_PROFILE), eq(QUERY), passagesCaptor.capture());
        assertFalse(passagesCaptor.getValue().contains(PASSAGE_3), "topK 之外的候选不得进入批量打分");
    }

    /**
     * 场景②-3：similarThreshold 过滤低分候选 + resultChunkCount 截断输出条数。
     */
    @Test
    void should_filterByThresholdAndLimitResult_when_rerank_given_thresholdAndResultChunkCount() {
        // given：topK 未配置按默认 50 全量打分；3 打分后 0.10 低于阈值 0.3 被过滤
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D), coarse(3L, 0.7D));
        RetrievalStrategyConfig cfg = enabledStrategy(null, 0.3F, 2);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2),
                        chunkWithContent(3L, PASSAGE_3)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2, PASSAGE_3)))
                .thenReturn(List.of(0.35D, 0.95D, 0.10D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：3 全量打分；0.10 被阈值剔除；按 resultChunkCount 保留 2 条
        verify(rerankClient, times(1)).scoreBatch(eq(RERANK_PROFILE), eq(QUERY), anyList());
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).chunkId());
        assertEquals(0.95D, result.get(0).score(), DELTA);
        assertEquals(1L, result.get(1).chunkId());
        assertEquals(0.35D, result.get(1).score(), DELTA);
    }

    /**
     * 场景②-4：resultChunkCount=1 时只输出重排最优候选（截断边界）。
     */
    @Test
    void should_returnSingleChunk_when_rerank_given_resultChunkCountOne() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, 0.0F, 1);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(List.of(0.4D, 0.6D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).chunkId());
    }

    /**
     * 场景②-5：重排分并列时保持粗排相对次序（稳定排序）。
     */
    @Test
    void should_keepCoarseRelativeOrder_when_rerank_given_equalRerankScores() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(List.of(0.5D, 0.5D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertEquals(1L, result.get(0).chunkId());
        assertEquals(2L, result.get(1).chunkId());
    }

    /**
     * 场景②-6：批量打分结果数量与候选数不符（对齐违约）→ 降级直出粗排，不向上抛。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_scoreBatchSizeMismatch() {
        // given：2 个候选只返回 1 个分数
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(List.of(0.9D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertSame(candidates, result);
    }

    /**
     * 场景②-7：批量打分结果含无分候选（对齐违约）→ 降级直出粗排，不静默按 0 分处理。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_scoreBatchContainsNullScore() {
        // given：阈值 0.5 会把「补零」的候选静默过滤，此处必须整体降级
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, 0.5F, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(Arrays.asList(null, 0.9D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：降级为粗排原序原分（两条都在，未被 0 分误过滤）
        assertSame(candidates, result);
        assertEquals(2, result.size());
    }

    /**
     * 场景③：批量打分依赖异常 → 记 warning 后降级直出粗排，不向上抛。
     */
    @Test
    void should_returnCoarseCandidatesWithoutThrow_when_rerank_given_scoreBatchThrows() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, 0.5F, 1);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenThrow(new RuntimeException("reranker 远程调用超时"));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：降级为粗排原序原分（含未截断的 2 条），异常不外抛
        assertSame(candidates, result);
        assertEquals(0.9D, result.get(0).score(), DELTA);
        verify(rerankClient, times(1)).scoreBatch(eq(RERANK_PROFILE), eq(QUERY), anyList());
    }

    /**
     * 场景③-2：正文回取依赖异常 → 同样降级直出粗排。
     */
    @Test
    void should_returnCoarseCandidates_when_rerank_given_chunkFetchThrows() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenThrow(new RuntimeException("知识库只读通道不可用"));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertSame(candidates, result);
        verifyNoInteractions(rerankClient);
    }

    /**
     * 场景④：chunk 正文回取不到的候选跳过打分，保留粗排分与相对次序置于尾部。
     */
    @Test
    void should_keepUnscoredCandidatesAtTail_when_rerank_given_missingChunkContent() {
        // given：候选1 正文未命中（粗排分 0.05 低于阈值），候选2 正常打分 0.9
        List<RankedChunk> candidates = List.of(coarse(1L, 0.05D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, 0.5F, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_2))).thenReturn(List.of(0.9D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：未打分候选不参与阈值过滤，置尾且保留粗排分与原来源文件名
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).chunkId());
        assertEquals(0.9D, result.get(0).score(), DELTA);
        assertEquals(1L, result.get(1).chunkId());
        assertEquals(0.05D, result.get(1).score(), DELTA);
        assertEquals("来源-1.pdf", result.get(1).sourceFile());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> passagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(rerankClient, times(1)).scoreBatch(eq(RERANK_PROFILE), eq(QUERY), passagesCaptor.capture());
        assertEquals(List.of(PASSAGE_2), passagesCaptor.getValue());
    }

    /**
     * 场景④-2：全部候选正文缺失 → 完全不发起批量打分调用，粗排原序输出。
     */
    @Test
    void should_skipRerankClient_when_rerank_given_allPassagesMissing() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, 0.5F, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of());

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).chunkId());
        verifyNoInteractions(rerankClient);
    }

    /**
     * 场景④-3：未配置 resultChunkCount 时，尾部未打分候选仍受 topK 之后的整体顺序约束（不截断）。
     */
    @Test
    void should_keepAllCandidates_when_rerank_given_nullResultChunkCount() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(2L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(2L, PASSAGE_2)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_2)))
                .thenReturn(List.of(0.1D, 0.2D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).chunkId());
        assertEquals(1L, result.get(1).chunkId());
    }

    /**
     * 场景⑤：kbId 与候选 chunkId 集合正确传递到 chunk 正文回取（单库隔离）。
     */
    @Test
    void should_passKbIdAndChunkIdsToChunkFetch_when_rerank_given_rerankEnabled() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D), coarse(4L, 0.8D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1), chunkWithContent(4L, PASSAGE_4)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1, PASSAGE_4)))
                .thenReturn(List.of(0.3D, 0.7D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then：以 kbId 等值过滤回取，chunkId 集合为参与精排的全部候选
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> chunkIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(knowledgeBaseApi).findChunksByKbIdAndChunkIds(eq(KB_ID), chunkIdsCaptor.capture());
        assertEquals(2, chunkIdsCaptor.getValue().size());
        assertTrue(chunkIdsCaptor.getValue().containsAll(List.of(1L, 4L)));
        assertEquals(2, result.size());
        assertEquals(4L, result.get(0).chunkId());
    }

    /**
     * 场景⑥：可打分候选仅一条（批量契约的退化边界）→ 仍走一次批量调用且分数正确回填。
     */
    @Test
    void should_scoreSingleCandidate_when_rerank_given_onlyOneScoreableCandidate() {
        // given
        List<RankedChunk> candidates = List.of(coarse(1L, 0.9D));
        RetrievalStrategyConfig cfg = enabledStrategy(50, null, null);
        when(knowledgeBaseApi.findChunksByKbIdAndChunkIds(eq(KB_ID), anyCollection()))
                .thenReturn(List.of(chunkWithContent(1L, PASSAGE_1)));
        when(rerankClient.scoreBatch(RERANK_PROFILE, QUERY, List.of(PASSAGE_1))).thenReturn(List.of(0.42D));

        // when
        List<RankedChunk> result = reranker.rerank(QUERY, candidates, cfg, KB_ID);

        // then
        assertEquals(1, result.size());
        assertEquals(0.42D, result.get(0).score(), DELTA);
    }
}

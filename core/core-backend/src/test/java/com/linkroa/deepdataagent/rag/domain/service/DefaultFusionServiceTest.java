package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultFusionService} 单元测试。
 *
 * <p>纯算法离线测试，被测服务无外部依赖，直接实例化，不启动 Spring 容器。</p>
 *
 * <p>覆盖场景：① RRF(rrfK=60) 手工算例（1/61 + 1/62 跨通道累积）；② WEIGHTED_SUM
 * min-max 归一化与跨通道加权累积；③ MISSING 通道（空列表/键缺失/权重缺失）不参与融合；
 * ④ 全同分归一取 1.0 与同分 chunkId 升序稳定排序；⑤ Top 截断（精排输入规模 50）；
 * ⑥ 空输入/null 入参防护。</p>
 *
 * @author DeepDataAgent
 */
class DefaultFusionServiceTest {

    /** 浮点断言容差 */
    private static final double DELTA = 1e-9;

    /** 被测 RRF 常数 k（与 RetrievalConstants.RRF_K 一致） */
    private static final int RRF_K = 60;

    /** 测试 chunkId：1 */
    private static final Long CHUNK_1 = 1L;

    /** 测试 chunkId：2 */
    private static final Long CHUNK_2 = 2L;

    /** 测试 chunkId：3 */
    private static final Long CHUNK_3 = 3L;

    /** 测试 chunkId：4 */
    private static final Long CHUNK_4 = 4L;

    /** 同分排序场景 chunkId（故意乱序构造以验证升序稳定输出） */
    private static final Long CHUNK_5 = 5L;

    /** 同分排序场景 chunkId */
    private static final Long CHUNK_9 = 9L;

    /** 截断场景 chunkId 起始值 */
    private static final Long TRUNCATION_ID_BASE = 1001L;

    /** 截断场景候选数（超出 Top 上限 10 条） */
    private static final int TRUNCATION_CANDIDATE_COUNT = RetrievalConstants.RERANK_TOP_K_DEFAULT + 10;

    /** WEIGHTED_SUM 权重：1.0 */
    private static final float WEIGHT_FULL = 1.0F;

    /** WEIGHTED_SUM 权重：0.5 */
    private static final float WEIGHT_HALF = 0.5F;

    /** 被测融合服务（无依赖，直接实例化） */
    private final DefaultFusionService fusionService = new DefaultFusionService();

    @Test
    void should_sumReciprocalRankAndAdoptFirstSourceFile_when_fuse_given_twoChannelsRrfK60() {
        // given：VECTOR 第 1 名 chunk1（来源 a.md）+ BM25 第 2 名 chunk1（来源 b.md），
        // 另含 VECTOR 第 2 名 chunk2（无来源）与 BM25 第 1 名 chunk3
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_1, RetrievalChannel.VECTOR, 0.9D, "a.md"),
                candidate(CHUNK_2, RetrievalChannel.VECTOR, 0.8D, null)));
        channelResults.put(RetrievalChannel.BM25, List.of(
                candidate(CHUNK_3, RetrievalChannel.BM25, 5.0D, "c.md"),
                candidate(CHUNK_1, RetrievalChannel.BM25, 4.0D, "b.md")));

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults, rrfConfig());

        // then：chunk1 = 1/(60+1) + 1/(60+2) 跨通道累积居首；sourceFile 取首个非空（VECTOR 的 a.md）
        assertEquals(3, fused.size());
        assertEquals(CHUNK_1, fused.get(0).chunkId());
        assertEquals(1.0D / (RRF_K + 1) + 1.0D / (RRF_K + 2), fused.get(0).score(), DELTA);
        assertEquals("a.md", fused.get(0).sourceFile());
        assertEquals(CHUNK_3, fused.get(1).chunkId());
        assertEquals(1.0D / (RRF_K + 1), fused.get(1).score(), DELTA);
        assertEquals(CHUNK_2, fused.get(2).chunkId());
        assertEquals(1.0D / (RRF_K + 2), fused.get(2).score(), DELTA);
        assertNull(fused.get(2).sourceFile());
    }

    @Test
    void should_normalizeMinMaxAndAccumulateWeight_when_fuse_given_twoChannelsWeightedSum() {
        // given：VECTOR 权重 1.0（分数 10/5/0 → 归一 1.0/0.5/0.0），
        // BM25 权重 0.5（分数 100/0 → 归一 1.0/0.0，chunk2 与 VECTOR 交叉累积）
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_1, RetrievalChannel.VECTOR, 10.0D, null),
                candidate(CHUNK_2, RetrievalChannel.VECTOR, 5.0D, "b.md"),
                candidate(CHUNK_3, RetrievalChannel.VECTOR, 0.0D, null)));
        channelResults.put(RetrievalChannel.BM25, List.of(
                candidate(CHUNK_2, RetrievalChannel.BM25, 100.0D, "b.md"),
                candidate(CHUNK_4, RetrievalChannel.BM25, 0.0D, "d.md")));

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults,
                weightedConfig(Map.of(RetrievalChannel.VECTOR, WEIGHT_FULL, RetrievalChannel.BM25, WEIGHT_HALF)));

        // then：chunk2 = 1.0×0.5 + 0.5×1.0 = 1.0 与 chunk1 同分，按 chunkId 升序；
        // chunk3（0.0）与 chunk4（0.0）沉底同样按 chunkId 升序
        assertEquals(4, fused.size());
        assertEquals(CHUNK_1, fused.get(0).chunkId());
        assertEquals(1.0D, fused.get(0).score(), DELTA);
        assertEquals(CHUNK_2, fused.get(1).chunkId());
        assertEquals(1.0D, fused.get(1).score(), DELTA);
        assertEquals("b.md", fused.get(1).sourceFile());
        assertEquals(CHUNK_3, fused.get(2).chunkId());
        assertEquals(0.0D, fused.get(2).score(), DELTA);
        assertEquals(CHUNK_4, fused.get(3).chunkId());
        assertEquals(0.0D, fused.get(3).score(), DELTA);
    }

    @Test
    void should_skipMissingChannels_when_fuse_given_graphEmptyListAndBm25KeyAbsent() {
        // given：GRAPH 为空列表（MISSING）、BM25 键缺失（MISSING），仅 VECTOR 参与
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_1, RetrievalChannel.VECTOR, 0.9D, null),
                candidate(CHUNK_2, RetrievalChannel.VECTOR, 0.8D, null)));
        channelResults.put(RetrievalChannel.GRAPH, List.of());

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults, rrfConfig());

        // then：仅 VECTOR 两条参与，GRAPH/BM25 不产生任何得分贡献
        assertEquals(2, fused.size());
        assertEquals(CHUNK_1, fused.get(0).chunkId());
        assertEquals(1.0D / (RRF_K + 1), fused.get(0).score(), DELTA);
        assertEquals(CHUNK_2, fused.get(1).chunkId());
        assertEquals(1.0D / (RRF_K + 2), fused.get(1).score(), DELTA);
    }

    @Test
    void should_skipChannelWhenWeightMissing_when_fuse_given_weightedSumPartialWeights() {
        // given：权重映射仅含 VECTOR，BM25 权重缺失 → 该通道按 0 权处理等同 MISSING
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_1, RetrievalChannel.VECTOR, 0.7D, "a.md")));
        channelResults.put(RetrievalChannel.BM25, List.of(
                candidate(CHUNK_2, RetrievalChannel.BM25, 9.0D, "b.md")));

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults,
                weightedConfig(Map.of(RetrievalChannel.VECTOR, WEIGHT_FULL)));

        // then：BM25 的 chunk2 不进入结果；单元素通道全同分归一取 1.0
        assertEquals(1, fused.size());
        assertEquals(CHUNK_1, fused.get(0).chunkId());
        assertEquals(1.0D, fused.get(0).score(), DELTA);
    }

    @Test
    void should_sortByChunkIdAscendingStably_when_fuse_given_uniformChannelScoresWeightedSum() {
        // given：通道内全同分（0.7），故意乱序构造；权重 0.5 使归一口径显式化
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_5, RetrievalChannel.VECTOR, 0.7D, null),
                candidate(CHUNK_2, RetrievalChannel.VECTOR, 0.7D, null),
                candidate(CHUNK_9, RetrievalChannel.VECTOR, 0.7D, null)));

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults,
                weightedConfig(Map.of(RetrievalChannel.VECTOR, WEIGHT_HALF)));

        // then：全同分归一统一取 1.0（贡献 0.5×1.0），同分按 chunkId 升序稳定输出
        assertEquals(3, fused.size());
        assertEquals(CHUNK_2, fused.get(0).chunkId());
        assertEquals(CHUNK_5, fused.get(1).chunkId());
        assertEquals(CHUNK_9, fused.get(2).chunkId());
        for (RankedChunk chunk : fused) {
            assertEquals(0.5D, chunk.score(), DELTA);
        }
    }

    @Test
    void should_truncateToRerankTopK_when_fuse_given_moreThanTopKCandidates() {
        // given：VECTOR 通道 60 个候选（超出截断上限 10 条）
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < TRUNCATION_CANDIDATE_COUNT; index++) {
            candidates.add(candidate(TRUNCATION_ID_BASE + index, RetrievalChannel.VECTOR, 1.0D - index, null));
        }
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, candidates);

        // when
        List<RankedChunk> fused = fusionService.fuse(channelResults, rrfConfig());

        // then：截断至精排输入规模 50，头部为 rank 1，尾部为 rank 50（第 51 名起被丢弃）
        assertEquals(RetrievalConstants.RERANK_TOP_K_DEFAULT, fused.size());
        assertEquals(TRUNCATION_ID_BASE, fused.get(0).chunkId());
        assertEquals(TRUNCATION_ID_BASE + RetrievalConstants.RERANK_TOP_K_DEFAULT - 1,
                fused.get(fused.size() - 1).chunkId());
        assertEquals(1.0D / (RRF_K + RetrievalConstants.RERANK_TOP_K_DEFAULT),
                fused.get(fused.size() - 1).score(), DELTA);
    }

    @Test
    void should_returnEmptyList_when_fuse_given_emptyOrNullChannelResults() {
        // given
        Map<RetrievalChannel, List<RetrievalCandidate>> emptyChannels = Map.of();
        FusionStrategyConfig cfg = rrfConfig();

        // when：空映射与 null 入参均按 MISSING 处理
        List<RankedChunk> fromEmpty = fusionService.fuse(emptyChannels, cfg);
        List<RankedChunk> fromNull = fusionService.fuse(null, cfg);

        // then
        assertTrue(fromEmpty.isEmpty());
        assertTrue(fromNull.isEmpty());
    }

    @Test
    void should_throwIllegalArgumentException_when_fuse_given_nullConfig() {
        // given
        Map<RetrievalChannel, List<RetrievalCandidate>> channelResults = new EnumMap<>(RetrievalChannel.class);
        channelResults.put(RetrievalChannel.VECTOR, List.of(
                candidate(CHUNK_1, RetrievalChannel.VECTOR, 0.9D, null)));

        // when & then：融合策略配置缺失无法确定策略，快速失败
        assertThrows(IllegalArgumentException.class, () -> fusionService.fuse(channelResults, null));
    }

    /**
     * 构造 RRF 融合配置（rrfK=60）。
     *
     * @return RRF 配置
     */
    private FusionStrategyConfig rrfConfig() {
        return new FusionStrategyConfig(FusionStrategyType.RRF, RRF_K, null);
    }

    /**
     * 构造 WEIGHTED_SUM 融合配置。
     *
     * @param channelDenseWeight 通道稠密权重映射
     * @return 加权求和配置
     */
    private FusionStrategyConfig weightedConfig(Map<RetrievalChannel, Float> channelDenseWeight) {
        return new FusionStrategyConfig(FusionStrategyType.WEIGHTED_SUM, null, channelDenseWeight);
    }

    /**
     * 构造单通道召回候选。
     *
     * @param chunkId    chunk 标识
     * @param channel    来源通道
     * @param score      通道内分数
     * @param sourceFile 来源文件名（可为 null）
     * @return 候选值对象
     */
    private RetrievalCandidate candidate(Long chunkId, RetrievalChannel channel, double score, String sourceFile) {
        return new RetrievalCandidate(chunkId, channel, score, sourceFile);
    }
}

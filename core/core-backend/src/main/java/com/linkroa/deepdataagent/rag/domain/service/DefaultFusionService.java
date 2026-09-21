package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 粗排融合默认实现（Stage 2）。
 * <p>纯算法服务，无外部依赖。以 {@code LinkedHashMap<Long, ChunkAccumulator>} 单遍累加
 * 各通道贡献（O(总候选数)），随后统一按「分数倒序 + chunkId 升序」稳定排序并截断
 * {@code Top = RetrievalConstants.RERANK_TOP_K_DEFAULT}（「截断 Top ≈ 50」，
 * 即精排输入规模，默认 {@code rerank_top_k = 50}）后输出。</p>
 *
 * <p>策略语义：</p>
 * <ul>
 *     <li>RRF：{@code score(d) = Σ_c 1/(rrfK + rank_c(d))}，{@code rank} 为通道内列表
 *     下标 + 1（1-based）；{@code rrfK} 取 {@code cfg.rrfK()}（缺失兜底
 *     {@link RetrievalConstants#RRF_K}）；</li>
 *     <li>WEIGHTED_SUM：{@code score(d) = Σ_c channelDenseWeight[c] × normScore_c(d)}，
 *     通道内分数先做 min-max 归一化到 [0,1]。</li>
 * </ul>
 *
 * <p>边界口径：</p>
 * <ul>
 *     <li>MISSING 通道（键缺失或候选列表为空）不参与融合、不产生得分贡献；</li>
 *     <li>WEIGHTED_SUM 下通道权重缺失或为 0 时该通道按 0 权处理，等同 MISSING
 *     直接跳过（「channelDenseWeight 缺失某通道时该通道权重按 0 处理
 *     （MISSING 等同）」）；</li>
 *     <li>min-max 归一化遇通道内全同分（max == min）时归一值统一
 *     取 1.0，即全同分候选一律按满额权重贡献，避免除零且保持通道间相对语义
 *     （该通道成员同等重要）；</li>
 *     <li>同一 chunkId 跨通道去重累积，sourceFile 取首个非空值。</li>
 * </ul>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultFusionService implements FusionService {

    /** 通道内全同分（max == min）时的 min-max 归一化取值：满额 1.0（取 1.0 并在此注释说明） */
    private static final double UNIFORM_NORMALIZED_SCORE = 1.0D;

    /** RRF 单位贡献分子（1/(rrfK+rank) 的分子常量） */
    private static final double RRF_UNIT_NUMERATOR = 1.0D;

    /** 通道内 1-based 首排名 */
    private static final int FIRST_RANK = 1;

    /** 通道权重缺省值：缺失或 0 权通道等同 MISSING，不参与融合 */
    private static final float MISSING_CHANNEL_WEIGHT = 0.0F;

    /**
     * 对多通道候选取粗排融合，返回全局有序 chunk 列表。
     *
     * @param channelResults 通道到候选列表的映射；null/空映射、缺失通道与空列表通道均按 MISSING 跳过
     * @param cfg            融合策略配置（fusionType / rrfK / channelDenseWeight），不可为 null
     * @return 全局有序候选列表（分数倒序 + chunkId 升序，截断 Top 后可能为空）
     * @throws IllegalArgumentException 当 {@code cfg} 或其 fusionType 为 null 时
     */
    @Override
    public List<RankedChunk> fuse(Map<RetrievalChannel, List<RetrievalCandidate>> channelResults,
                                  FusionStrategyConfig cfg) {
        if (ObjectUtils.isEmpty(cfg) || ObjectUtils.isEmpty(cfg.fusionType())) {
            throw new IllegalArgumentException("融合策略配置不能为空");
        }
        if (ObjectUtils.isEmpty(channelResults)) {
            return List.of();
        }
        Map<Long, ChunkAccumulator> accumulators = new LinkedHashMap<>();
        switch (cfg.fusionType()) {
            case RRF -> accumulateRrf(channelResults, accumulators, resolveRrfK(cfg));
            case WEIGHTED_SUM -> accumulateWeightedSum(channelResults, accumulators, cfg.channelDenseWeight());
            default -> throw new IllegalArgumentException("不支持的融合策略类型: " + cfg.fusionType());
        }
        return sortAndTruncate(accumulators);
    }

    /**
     * 解析 RRF 常数 k：正常由 {@link FusionStrategyConfig} 紧凑构造器兜底，此处再防御一次。
     *
     * @param cfg 融合配置
     * @return rrfK，缺失时取 {@link RetrievalConstants#RRF_K}
     */
    private int resolveRrfK(FusionStrategyConfig cfg) {
        return ObjectUtils.isEmpty(cfg.rrfK()) ? RetrievalConstants.RRF_K : cfg.rrfK();
    }

    /**
     * RRF 累加：每通道按列表下标 + 1 作为 1-based rank，贡献 {@code 1/(rrfK + rank)}。
     *
     * @param channelResults 通道候选映射
     * @param accumulators   chunkId 累积器（就地写入）
     * @param rrfK           RRF 常数 k
     */
    private void accumulateRrf(Map<RetrievalChannel, List<RetrievalCandidate>> channelResults,
                               Map<Long, ChunkAccumulator> accumulators, int rrfK) {
        for (Map.Entry<RetrievalChannel, List<RetrievalCandidate>> entry : channelResults.entrySet()) {
            List<RetrievalCandidate> candidates = entry.getValue();
            if (CollectionUtils.isEmpty(candidates)) {
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                RetrievalCandidate candidate = candidates.get(index);
                if (isInvalidCandidate(candidate)) {
                    continue;
                }
                int rank = index + FIRST_RANK;
                double contribution = RRF_UNIT_NUMERATOR / (rrfK + rank);
                absorb(accumulators, candidate, contribution);
            }
        }
    }

    /**
     * WEIGHTED_SUM 累加：通道内分数 min-max 归一化后乘以通道权重跨通道累积。
     *
     * @param channelResults 通道候选映射
     * @param accumulators   chunkId 累积器（就地写入）
     * @param channelWeights 通道稠密权重映射（可为 null；缺失/0 权通道按 MISSING 跳过）
     */
    private void accumulateWeightedSum(Map<RetrievalChannel, List<RetrievalCandidate>> channelResults,
                                       Map<Long, ChunkAccumulator> accumulators,
                                       Map<RetrievalChannel, Float> channelWeights) {
        for (Map.Entry<RetrievalChannel, List<RetrievalCandidate>> entry : channelResults.entrySet()) {
            List<RetrievalCandidate> candidates = entry.getValue();
            if (CollectionUtils.isEmpty(candidates)) {
                continue;
            }
            float weight = resolveChannelWeight(channelWeights, entry.getKey());
            if (weight <= MISSING_CHANNEL_WEIGHT) {
                continue;
            }
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            boolean scored = false;
            for (RetrievalCandidate candidate : candidates) {
                if (isInvalidCandidate(candidate)) {
                    continue;
                }
                scored = true;
                min = Math.min(min, candidate.score());
                max = Math.max(max, candidate.score());
            }
            if (!scored) {
                continue;
            }
            boolean uniform = Double.compare(min, max) == 0;
            double range = max - min;
            for (RetrievalCandidate candidate : candidates) {
                if (isInvalidCandidate(candidate)) {
                    continue;
                }
                double normalized = uniform
                        ? UNIFORM_NORMALIZED_SCORE
                        : (candidate.score() - min) / range;
                absorb(accumulators, candidate, weight * normalized);
            }
        }
    }

    /**
     * 查询通道权重，缺失（映射为 null 或键不存在）时按 0 处理。
     *
     * @param channelWeights 通道权重映射（可为 null）
     * @param channel        目标通道
     * @return 通道权重，缺失按 0 处理
     */
    private float resolveChannelWeight(Map<RetrievalChannel, Float> channelWeights, RetrievalChannel channel) {
        if (ObjectUtils.isEmpty(channelWeights)) {
            return MISSING_CHANNEL_WEIGHT;
        }
        Float weight = channelWeights.get(channel);
        return ObjectUtils.isEmpty(weight) ? MISSING_CHANNEL_WEIGHT : weight;
    }

    /**
     * 累积单条候选：chunkId 去重累加得分，sourceFile 取首个非空。
     *
     * @param accumulators 累积器映射
     * @param candidate    候选
     * @param contribution 本通道贡献分
     */
    private void absorb(Map<Long, ChunkAccumulator> accumulators, RetrievalCandidate candidate, double contribution) {
        ChunkAccumulator accumulator = accumulators.computeIfAbsent(candidate.chunkId(), key -> new ChunkAccumulator());
        accumulator.addScore(contribution);
        if (StringUtils.isBlank(accumulator.sourceFile()) && StringUtils.isNotBlank(candidate.sourceFile())) {
            accumulator.adoptSourceFile(candidate.sourceFile());
        }
    }

    /**
     * 候选合法性防御性校验：候选对象或其 chunkId 为 null 时跳过（不参与融合）。
     *
     * @param candidate 候选
     * @return true 表示无效候选
     */
    private boolean isInvalidCandidate(RetrievalCandidate candidate) {
        return ObjectUtils.isEmpty(candidate) || ObjectUtils.isEmpty(candidate.chunkId());
    }

    /**
     * 统一收尾：分数倒序 + chunkId 升序稳定排序，截断 Top（精排输入规模）后输出。
     *
     * @param accumulators chunkId 累积器
     * @return 全局有序候选列表
     */
    private List<RankedChunk> sortAndTruncate(Map<Long, ChunkAccumulator> accumulators) {
        Comparator<RankedChunk> comparator = Comparator.comparingDouble(RankedChunk::score).reversed()
                .thenComparing(RankedChunk::chunkId);
        return accumulators.entrySet().stream()
                .map(entry -> new RankedChunk(entry.getKey(), entry.getValue().score(), entry.getValue().sourceFile()))
                .sorted(comparator)
                .limit(RetrievalConstants.RERANK_TOP_K_DEFAULT)
                .toList();
    }

    /**
     * 单 chunk 跨通道累积器：可变得分 + 首个非空来源文件名。
     */
    private static final class ChunkAccumulator {

        /** 累积得分 */
        private double score;

        /** 来源文件名（首个非空） */
        private String sourceFile;

        /**
         * 累加一个通道的贡献分。
         *
         * @param contribution 贡献分
         */
        void addScore(double contribution) {
            this.score += contribution;
        }

        /**
         * 写入来源文件名（调用方保证仅在尚未记录非空值时调用）。
         *
         * @param sourceFile 非空来源文件名
         */
        void adoptSourceFile(String sourceFile) {
            this.sourceFile = sourceFile;
        }

        double score() {
            return this.score;
        }

        String sourceFile() {
            return this.sourceFile;
        }
    }
}

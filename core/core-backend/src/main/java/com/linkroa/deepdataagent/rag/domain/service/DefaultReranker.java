package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.infrastructure.client.RerankClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 精排服务默认实现（Stage 3，默认关闭 + 依赖异常降级直出粗排）。
 * <p>执行顺序与严格一致：</p>
 * <ol>
 *   <li>{@code rerankConfig} 缺失或 {@code enabled=false} → 原样直出粗排候选，不产生任何远程调用；</li>
 *   <li>候选截断至 {@code rerankConfig.topK}（未配置/非正数回退
 *       {@link RetrievalConstants#RERANK_TOP_K_DEFAULT}）；</li>
 *   <li>经 {@link KnowledgeBaseApi#findChunksByKbIdAndChunkIds(Long, java.util.Collection)} 按 {@code kbId}
 *       批量回取 chunk 正文（单库隔离，跨库/已删除 ID 不命中）；</li>
 *   <li>一次 {@link RerankClient#scoreBatch(String, String, List)} 批量打分（多候选只发生一次远程调用），
 *       返回分数按入参顺序回填，再按重排分降序（同分保持粗排相对次序）；</li>
 *   <li>{@code score < similarThreshold} 过滤（阈值未配置则跳过过滤）；</li>
 *   <li>截断至 {@code resultChunkCount}（未配置/非正数则不截断）。</li>
 * </ol>
 *
 * <p><b>降级语义</b>：回取正文与打分过程中的任何运行时异常（远程不可用、超时、参数非法）统一捕获后
 * 记 warning 并直出入参粗排结果，绝不向编排层抛出——检索可用性优先于精排准确性。
 * 打分改为一次批量请求后，降级判定粒度随之变粗：<b>这一次请求失败即等价于全部候选无分</b>，
 * 不再存在「部分候选打分成功」的中间态（逐候选打分时代码可在异常前保留已打分的候选）。</p>
 *
 * <p><b>正文缺失的候选</b>：无法取得 passage 即无法取得重排分，故跳过打分、保留粗排分与原相对次序，
 * 置于已打分结果之后（不参与 {@code similarThreshold} 过滤，因其分数为粗排分，口径不可比），
 * 但仍受 {@code resultChunkCount} 整体截断约束。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultReranker implements Reranker {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DefaultReranker.class);

    /** 库级重排打分端口 */
    private final RerankClient rerankClient;

    /** 知识库只读消费面（回取 chunk 正文） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /**
     * 构造精排服务。
     *
     * @param rerankClient     库级重排打分端口
     * @param knowledgeBaseApi 知识库只读消费面
     */
    public DefaultReranker(RerankClient rerankClient, KnowledgeBaseApi knowledgeBaseApi) {
        this.rerankClient = rerankClient;
        this.knowledgeBaseApi = knowledgeBaseApi;
    }

    @Override
    public List<RankedChunk> rerank(String query, List<RankedChunk> candidates, RetrievalStrategyConfig cfg, Long kbId) {
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }
        RerankModelConfig rerankConfig = resolveRerankConfig(cfg);
        if (ObjectUtils.isEmpty(rerankConfig) || !rerankConfig.isEnabled()) {
            return candidates;
        }
        try {
            return doRerank(query, candidates, cfg, rerankConfig, kbId);
        } catch (RuntimeException e) {
            log.warn("精排依赖异常，降级直出粗排结果：kbId=[{}], candidateCount=[{}]", kbId, candidates.size(), e);
            return candidates;
        }
    }

    /**
     * 执行一次完整精排（topK 截断 → 正文回取 → 一次批量打分 → 降序 → 阈值过滤 → 结果截断）。
     *
     * @param query        用户查询文本
     * @param candidates   粗排候选（非空）
     * @param cfg          检索策略配置（提供 similarThreshold / resultChunkCount）
     * @param rerankConfig 重排模型配置（已确认启用）
     * @param kbId         知识库ID（正文回取的隔离条件）
     * @return 精排后候选列表
     */
    private List<RankedChunk> doRerank(String query, List<RankedChunk> candidates, RetrievalStrategyConfig cfg,
                                       RerankModelConfig rerankConfig, Long kbId) {
        List<RankedChunk> truncated = truncateByTopK(candidates, resolveTopK(rerankConfig.topK()));
        Map<Long, String> passageById = loadPassages(kbId, truncated);
        List<RankedChunk> scorable = new ArrayList<>(truncated.size());
        List<RankedChunk> unscored = new ArrayList<>();
        List<String> passages = new ArrayList<>(truncated.size());
        for (RankedChunk candidate : truncated) {
            String passage = passageById.get(candidate.chunkId());
            if (StringUtils.isBlank(passage)) {
                // 正文缺失（跨库、已删除（行消失）或未被回取）无法打分：保留粗排分与原相对次序，置于结果尾部
                unscored.add(candidate);
                continue;
            }
            scorable.add(candidate);
            passages.add(passage);
        }
        List<Double> scores = scoreCandidates(rerankConfig.modelProfileId(), query, passages);
        List<RankedChunk> scored = new ArrayList<>(scorable.size());
        for (int i = 0; i < scorable.size(); i++) {
            RankedChunk candidate = scorable.get(i);
            scored.add(new RankedChunk(candidate.chunkId(), scores.get(i), candidate.sourceFile()));
        }
        scored.sort(Comparator.comparingDouble(RankedChunk::score).reversed());
        List<RankedChunk> result = filterByThreshold(scored, cfg.similarThreshold());
        result.addAll(unscored);
        return limitResultCount(result, cfg.resultChunkCount());
    }

    /**
     * 一次批量打分并校验返回对齐性（可打分候选为空时不发起远程调用）。
     * <p>端口契约要求返回列表与入参等长同序且不含 null；违约即视为协议异常抛出，
     * 由 {@link #rerank} 的降级分支统一捕获直出粗排，避免以零分占位造成静默过滤。</p>
     *
     * @param modelProfileId 重排模型 profileId
     * @param query          用户查询文本
     * @param passages       可打分候选的正文列表（顺序即回填顺序）
     * @return 与 {@code passages} 等长同序的重排分列表
     */
    private List<Double> scoreCandidates(String modelProfileId, String query, List<String> passages) {
        if (CollectionUtils.isEmpty(passages)) {
            return Collections.emptyList();
        }
        List<Double> scores = rerankClient.scoreBatch(modelProfileId, query, passages);
        if (CollectionUtils.size(scores) != passages.size()) {
            throw new IllegalStateException("重排批量打分结果数量不符: 期望 "
                    + passages.size() + ", 实际 " + CollectionUtils.size(scores));
        }
        for (int i = 0; i < scores.size(); i++) {
            if (ObjectUtils.isEmpty(scores.get(i))) {
                throw new IllegalStateException("重排批量打分结果存在无分候选: index=" + i);
            }
        }
        return scores;
    }

    /**
     * 取策略配置中的重排模型配置。
     *
     * @param cfg 检索策略配置，可为空
     * @return 重排模型配置；策略配置为空时返回 {@code null}
     */
    private RerankModelConfig resolveRerankConfig(RetrievalStrategyConfig cfg) {
        if (ObjectUtils.isEmpty(cfg)) {
            return null;
        }
        return cfg.rerankConfig();
    }

    /**
     * 解析精排候选截断上限。
     *
     * @param topK 配置的重排 top K，可为空
     * @return 有效的 top K；未配置或非正数回退 {@link RetrievalConstants#RERANK_TOP_K_DEFAULT}
     */
    private int resolveTopK(Integer topK) {
        if (ObjectUtils.isEmpty(topK) || topK <= 0) {
            return RetrievalConstants.RERANK_TOP_K_DEFAULT;
        }
        return topK;
    }

    /**
     * 按 top K 截断候选（保持粗排相对次序）。
     *
     * @param candidates 粗排候选
     * @param topK       截断上限（正数）
     * @return 截断后的候选副本
     */
    private List<RankedChunk> truncateByTopK(List<RankedChunk> candidates, int topK) {
        if (candidates.size() <= topK) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, topK));
    }

    /**
     * 批量回取候选 chunk 正文（单库隔离，异常由上层统一降级）。
     *
     * @param kbId      知识库ID
     * @param truncated 参与精排的候选
     * @return chunkId 到正文的映射；无有效 ID 或未命中时为空映射
     */
    private Map<Long, String> loadPassages(Long kbId, List<RankedChunk> truncated) {
        Set<Long> chunkIds = new LinkedHashSet<>(truncated.size());
        for (RankedChunk candidate : truncated) {
            if (ObjectUtils.isNotEmpty(candidate.chunkId())) {
                chunkIds.add(candidate.chunkId());
            }
        }
        Map<Long, String> passageById = new HashMap<>(chunkIds.size());
        if (CollectionUtils.isEmpty(chunkIds)) {
            return passageById;
        }
        List<Chunk> chunks = knowledgeBaseApi.findChunksByKbIdAndChunkIds(kbId, chunkIds);
        if (CollectionUtils.isEmpty(chunks)) {
            log.warn("精排回取 chunk 正文为空，全部候选按未打分置于尾部：kbId=[{}], chunkIdCount=[{}]",
                    kbId, chunkIds.size());
            return passageById;
        }
        for (Chunk chunk : chunks) {
            passageById.put(chunk.id(), chunk.chunkContent());
        }
        return passageById;
    }

    /**
     * 按相似度阈值过滤已打分候选。
     *
     * @param scored           已按重排分降序的候选
     * @param similarThreshold 相似度阈值，可为空（空表示不过滤）
     * @return 过滤后的候选副本（保持入参顺序）
     */
    private List<RankedChunk> filterByThreshold(List<RankedChunk> scored, Float similarThreshold) {
        List<RankedChunk> kept = new ArrayList<>(scored.size());
        if (ObjectUtils.isEmpty(similarThreshold)) {
            kept.addAll(scored);
            return kept;
        }
        float threshold = similarThreshold;
        for (RankedChunk chunk : scored) {
            if (chunk.score() >= threshold) {
                kept.add(chunk);
            }
        }
        return kept;
    }

    /**
     * 按结果返回数量截断。
     *
     * @param ranked           精排后的完整候选（已打分在前、未打分置尾）
     * @param resultChunkCount 结果返回数量，可为空（空或非正数表示不截断）
     * @return 截断后的候选列表
     */
    private List<RankedChunk> limitResultCount(List<RankedChunk> ranked, Integer resultChunkCount) {
        if (ObjectUtils.isEmpty(resultChunkCount) || resultChunkCount <= 0 || ranked.size() <= resultChunkCount) {
            return ranked;
        }
        return new ArrayList<>(ranked.subList(0, resultChunkCount));
    }
}

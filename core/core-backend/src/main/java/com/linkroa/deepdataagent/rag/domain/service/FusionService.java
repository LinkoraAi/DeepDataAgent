package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.FusionStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;

import java.util.List;
import java.util.Map;

/**
 * 粗排融合服务（Stage 2：RRF / 加权求和）。
 * <p>O(n) 累积分算法：RRF 按各通道 1-based rank 计 {@code 1/(rrfK+rank)}、
 * WEIGHTED_SUM 按归一化分数 × 通道权重；MISSING/未启用通道不参与；
 * 输出按分数倒序 + chunkId 升序稳定排序并截断。默认 RRF（rrfK=60）。</p>
 */
public interface FusionService {

    /**
     * 对多通道候选取粗排融合，返回全局有序 chunk 列表。
     *
     * @param channelResults 通道到候选列表的映射（不含 MISSING 通道）
     * @param cfg            融合策略配置（fusionType / rrfK / channelDenseWeight）
     * @return 全局有序候选列表（分数倒序 + chunkId 升序，可截断为空）
     */
    List<RankedChunk> fuse(Map<RetrievalChannel, List<RetrievalCandidate>> channelResults,
                           FusionStrategyConfig cfg);
}
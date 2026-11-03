package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;

/**
 * 检索策略配置值对象。
 *
 * @param strategyType           策略类型（NAIVE / MIX）
 * @param rewriteQuestion        是否启用问题改写
 * @param resultChunkCount       结果返回数量
 * @param similarThreshold       相似度阈值
 * @param rerankConfig           重排模型配置
 * @param fusionConfig           融合策略配置
 * @param channelQueryCountConfig 检索通道召回数量配置（向量/BM25/图谱，可空表示未配置，
 *                                消费侧归一默认值 20；图谱数量为实体/关系路与入场配额共用的单值口径）
 */
public record RetrievalStrategyConfig(
        RetrievalStrategyType strategyType,
        Boolean rewriteQuestion,
        Integer resultChunkCount,
        Float similarThreshold,
        RerankModelConfig rerankConfig,
        FusionStrategyConfig fusionConfig,
        ChannelQueryCountConfig channelQueryCountConfig
) {

    public RetrievalStrategyConfig {
        if (strategyType == null) {
            throw new IllegalArgumentException("检索策略类型不能为空");
        }
    }
}

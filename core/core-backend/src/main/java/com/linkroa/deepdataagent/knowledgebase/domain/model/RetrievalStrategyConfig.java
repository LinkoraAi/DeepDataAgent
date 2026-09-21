package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;

/**
 * 检索策略配置值对象。
 *
 * @param strategyType     策略类型（NAIVE / MIX）
 * @param rewriteQuestion  是否启用问题改写
 * @param resultChunkCount 结果返回数量
 * @param similarThreshold 相似度阈值
 * @param rerankConfig     重排模型配置
 * @param fusionConfig     融合策略配置
 */
public record RetrievalStrategyConfig(
        RetrievalStrategyType strategyType,
        Boolean rewriteQuestion,
        Integer resultChunkCount,
        Float similarThreshold,
        RerankModelConfig rerankConfig,
        FusionStrategyConfig fusionConfig
) {

    public RetrievalStrategyConfig {
        if (strategyType == null) {
            throw new IllegalArgumentException("检索策略类型不能为空");
        }
    }
}

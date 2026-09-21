package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 融合策略配置值对象（RRF 或加权求和）。
 *
 * @param fusionType        融合类型
 * @param rrfK              RRF 常数 k（仅 RRF 时使用，默认 60）
 * @param channelDenseWeight 各通道稠密权重映射（仅 WEIGHTED_SUM 时使用）
 */
public record FusionStrategyConfig(
        com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType fusionType,
        Integer rrfK,
        java.util.Map<com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel, Float> channelDenseWeight
) {

    /** RRF 默认 k 值 */
    public static final int DEFAULT_RRF_K = 60;

    public FusionStrategyConfig {
        if (fusionType == null) {
            throw new IllegalArgumentException("融合策略类型不能为空");
        }
        if (fusionType == com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FusionStrategyType.RRF && rrfK == null) {
            rrfK = DEFAULT_RRF_K;
        }
    }
}

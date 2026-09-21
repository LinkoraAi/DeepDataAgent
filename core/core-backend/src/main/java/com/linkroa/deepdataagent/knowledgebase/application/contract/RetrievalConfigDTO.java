package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 检索策略配置契约（跨 BC Published Language）。
 * <p>提供方为 knowledgebase BC，消费方为 rag BC：消费方在绑定知识库时需要读取/写入检索策略，
 * 但不允许直接依赖本 BC 的领域值对象 {@code RetrievalStrategyConfig}，因此以本记录作为契约载体。</p>
 *
 * <p>与领域值对象的差异：策略类型以字符串传递（避免枚举跨 BC 耦合），重排/融合配置以 JSON 字符串传递
 * （避免复制本 BC 内部结构）。字段取值范围在紧凑构造器中校验，非法入参在跨边界处即被拒绝。</p>
 *
 * @param strategyType     检索策略类型名（NAIVE / MIX），必填
 * @param rewriteQuestion  是否启用问题改写，可为空（空表示不启用）
 * @param resultChunkCount 结果返回数量，可为空，取值 1~{@value #RESULT_CHUNK_COUNT_MAX}
 * @param similarThreshold 相似度阈值，可为空，取值 0~{@value #SIMILAR_THRESHOLD_MAX}
 * @param graphEnabled     是否启用图谱检索通道，可为空（空表示不启用）
 * @param rerankConfig     重排配置 JSON 文本，可为空
 * @param fusionConfig     融合策略配置 JSON 文本，可为空
 */
public record RetrievalConfigDTO(
        String strategyType,
        Boolean rewriteQuestion,
        Integer resultChunkCount,
        Float similarThreshold,
        Boolean graphEnabled,
        String rerankConfig,
        String fusionConfig
) {

    /** 结果返回数量上限。 */
    public static final int RESULT_CHUNK_COUNT_MAX = 100;

    /** 相似度阈值上限。 */
    public static final float SIMILAR_THRESHOLD_MAX = 1.0F;

    public RetrievalConfigDTO {
        if (StringUtils.isBlank(strategyType)) {
            throw new IllegalArgumentException("检索策略类型不能为空");
        }
        if (ObjectUtils.isNotEmpty(resultChunkCount)
                && (resultChunkCount < 1 || resultChunkCount > RESULT_CHUNK_COUNT_MAX)) {
            throw new IllegalArgumentException("结果返回数量必须在1到" + RESULT_CHUNK_COUNT_MAX + "之间");
        }
        if (ObjectUtils.isNotEmpty(similarThreshold)
                && (similarThreshold < 0F || similarThreshold > SIMILAR_THRESHOLD_MAX)) {
            throw new IllegalArgumentException("相似度阈值必须在0到" + SIMILAR_THRESHOLD_MAX + "之间");
        }
    }
}

package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 检索通道召回数量配置契约（跨 BC Published Language，
 * {@code retrieval_strategy.channelQueryCountConfig} 节点的类型化载体）。
 * <p>与领域值对象的边界同 {@link RetrievalConfigDTO}：供 rag BC 等消费方跨边界读写，
 * 不依赖 knowledge BC 领域模型。三字段均可为 {@code null}（未配置，运行时按默认值 20 处理）；
 * 提供时取值范围在紧凑构造器中校验，非法入参在跨边界处即被拒绝。</p>
 * <p>图谱数量为实体路与关系路共用的单值口径（对齐 LightRAG {@code top_k}），
 * 同时约束图谱关联 chunk 候选的入场配额（E1）。</p>
 *
 * @param vectorQueryCount 向量通道召回数量，可为空，取值 1~{@value #QUERY_COUNT_MAX}
 * @param bm25QueryCount   BM25 通道召回数量，可为空，取值 1~{@value #QUERY_COUNT_MAX}
 * @param graphQueryCount  图谱通道召回数量（实体/关系路与入场配额共用），可为空，取值 1~{@value #QUERY_COUNT_MAX}
 */
public record ChannelQueryCountDTO(
        Integer vectorQueryCount,
        Integer bm25QueryCount,
        Integer graphQueryCount
) {

    /** 通道召回数量上限。 */
    public static final int QUERY_COUNT_MAX = 100;

    public ChannelQueryCountDTO {
        validateQueryCount(vectorQueryCount, "vectorQueryCount");
        validateQueryCount(bm25QueryCount, "bm25QueryCount");
        validateQueryCount(graphQueryCount, "graphQueryCount");
    }

    /**
     * 校验单个通道召回数量：空跳过；提供时必须为 1~{@value #QUERY_COUNT_MAX} 的整数。
     *
     * @param count     数量值，可为空
     * @param fieldName 字段名（错误消息用）
     */
    private static void validateQueryCount(Integer count, String fieldName) {
        if (ObjectUtils.isEmpty(count)) {
            return;
        }
        if (count < 1 || count > QUERY_COUNT_MAX) {
            throw new IllegalArgumentException(
                    "通道召回数量（" + fieldName + "）必须在1到" + QUERY_COUNT_MAX + "之间");
        }
    }
}

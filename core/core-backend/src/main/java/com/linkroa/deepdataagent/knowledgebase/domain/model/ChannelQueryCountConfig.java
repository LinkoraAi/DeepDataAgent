package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 检索通道召回数量配置值对象（库级，{@code retrieval_strategy.channelQueryCountConfig} 节点）。
 * <p>三字段均可为 {@code null}，{@code null} 表示「未配置」——与「显式配置 20」语义可区分，
 * 由检索执行链消费侧归一为默认值 20（未配置存量库行为不变）。数量口径：</p>
 * <ul>
 *   <li>{@code vectorQueryCount}：向量通道召回上限（HNSW 近邻检索条数）；</li>
 *   <li>{@code bm25QueryCount}：BM25 全文通道召回上限（bm25 索引有序扫描条数）；</li>
 *   <li>{@code graphQueryCount}：图谱通道召回上限——实体路与关系路共用同一数量
 *       （对齐 LightRAG {@code top_k} 单值口径），同时约束图谱关联 chunk 候选的入场配额（E1）。</li>
 * </ul>
 * <p>NAIVE 策略下图谱数量不保存（该策略不触发图谱通道）；MIX 策略下可省略（运行时回落默认值）。
 * 取值范围约束（提供时 1~100 整数）由写入口校验器承接，本值对象仅承载。</p>
 *
 * @param vectorQueryCount 向量通道召回数量，可为空（未配置）
 * @param bm25QueryCount   BM25 通道召回数量，可为空（未配置）
 * @param graphQueryCount  图谱通道召回数量（实体/关系路与 E1 入场配额共用），可为空（未配置）
 */
public record ChannelQueryCountConfig(
        Integer vectorQueryCount,
        Integer bm25QueryCount,
        Integer graphQueryCount
) {
}

package com.linkroa.deepdataagent.rag.domain.model;

/**
 * 关系贡献记录：单个来源 chunk 对某条无向关系边的权重贡献。
 * <p>抽取产物保持逐 chunk 独立记录（普通关系 1.0、belongs_to 边 {@code BELONGS_TO_WEIGHT} 10.0），
 * 到合并端按「{@code weight = Σ{贡献记录全额 | source ∉ 图边已存 sourceIds} + 已存 weight}」
 * 与 LightRAG 同构累加，避免均摊式在混合权重域的数学失真。</p>
 *
 * @param sourceId 贡献来源标识（抽取阶段为 chunk sequence，合并前由 Worker 重写为真实 chunkId；可为 null 表示未知来源）
 * @param weight   本来源贡献的全额权重（不参与均摊折算）
 */
public record RelationContribution(Long sourceId, double weight) {
}

package com.linkroa.deepdataagent.rag.domain.model;

import java.util.List;

/**
 * 结构化图谱检索结果值对象（知识图谱命中的实体/关系/有序 chunk 原文集合）。
 * <p>实体与关系均为携带图谱属性的结构化命中（不再是裸名称列表），供上下文渲染逐行
 * 输出精简字段集 JSON；关系列表为「关系路命中 + 1 跳关联边转换记录」按归一端点对
 * 去重后的关系视图。GRAPH 通道 MISSING（实体路与关系路均无命中）时各字段为空列表。</p>
 *
 * @param entities  实体候选集（实体路命中与关系路端点逐位交错合并，去重保序）
 * @param relations 关系视图（关系路命中 ∪ 关联边转换记录，按归一端点对去重）
 * @param chunks    全局有序 chunk 列表（供上下文与 raw_data）
 */
public record KgSearchResult(
        List<EntityHit> entities,
        List<RelationHit> relations,
        List<RankedChunk> chunks
) {
}

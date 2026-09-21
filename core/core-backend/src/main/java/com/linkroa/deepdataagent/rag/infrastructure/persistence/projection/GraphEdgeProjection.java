package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 1 跳关联边命中投影（只读检索结果承载，对应 ③ 关联边查询）。
 * <p>端点对一律为 SQL 侧 {@code LEAST/GREATEST} 无向归一后的字典序方向
 * （sourceName &lt;= targetName），与 {@code relation_info_vector} 落库方向一致；
 * 历史双向残留行已在 SQL 侧按归一对折叠为 {@code updated_at} 最新的一行。</p>
 * <p>展示属性来自 {@code relation_edge_graph.properties}
 * （{@code description}/{@code keywordsRaw}(JSON 数组文本)/{@code weight}/{@code filePathsRaw}(JSON 数组文本)）
 * 与列级 {@code createdAt}；{@code chunkIdsRaw} 为单列 COALESCE 后的账本 JSON 数组文本
 * （向量表 {@code chunk_ids} 非空数组优先，空数组回落边行 {@code properties.sourceIds}）；
 * {@code edgeDegree} 为两端节点度数之和，仅作通道内排序键。</p>
 *
 * @see com.linkroa.deepdataagent.rag.domain.model.GraphEdgeHit
 */
@Data
public class GraphEdgeProjection {

    /** 源端点（字典序较小者） */
    private String sourceName;

    /** 目标端点（字典序较大者） */
    private String targetName;

    /** 边度数（两端节点度数之和，实时现算） */
    private Integer edgeDegree;

    /** 边权重（缺失时 SQL 侧已取 1.0） */
    private Double weight;

    /** chunk 账本 JSON 数组文本（向量表账本，空数组时已回落边行 sourceIds） */
    private String chunkIdsRaw;

    /** 边描述（图行 {@code properties.description}） */
    private String description;

    /** 边关键词 JSON 数组文本（图行 {@code properties.keywords}，可为 null） */
    private String keywordsRaw;

    /** 来源文件路径列表 JSON 数组文本（图行 {@code properties.filePaths}，由仓储层宽容解析为列表） */
    private String filePathsRaw;

    /** 图行创建时间（列级 {@code created_at}） */
    private OffsetDateTime createdAt;
}

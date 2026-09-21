package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 关系路向量命中投影（只读检索结果承载，对应 ②）。
 * <p>端点对为 {@code relation_info_vector} 落库方向的字典序归一端点（sourceName &lt;= targetName）；
 * {@code score} 为余弦相似度（{@code 1 - 余弦距离}，越高越相关）；{@code chunkIdsRaw} 为单列
 * COALESCE 后的账本 JSON 数组文本（向量表 {@code chunk_ids} 非空数组优先，空数组回落图行
 * {@code properties.sourceIds} 整数组）。</p>
 * <p>展示属性来自 1:1 的 {@code relation_edge_graph} 图行：{@code description}/{@code filePathsRaw}
 * 取 {@code properties} 文本，{@code keywordsRaw} 取 {@code properties.keywords} 的
 * <b>JSON 数组文本</b>（由仓储层经 {@code jsonToStringList} 消化），{@code weight} 为
 * {@code properties.weight} 数值（SQL 侧缺省 1.0），{@code createdAt} 取图行列值；
 * {@code graphMissing} 为真表示图行缺失，由仓储层 WARN 并剔除该命中。</p>
 */
@Data
public class RelationVectorHitProjection {

    /** 源实体名称（字典序较小端） */
    private String sourceName;

    /** 目标实体名称（字典序较大端） */
    private String targetName;

    /** 余弦相似度得分（1 - 余弦距离） */
    private Double score;

    /** chunk 账本 JSON 数组文本（向量表账本，空数组时已回落图行 sourceIds） */
    private String chunkIdsRaw;

    /** 关系描述（图行 {@code properties.description}） */
    private String description;

    /** 关系关键词 JSON 数组文本（图行 {@code properties.keywords}，可为 null） */
    private String keywordsRaw;

    /** 关系权重（图行 {@code properties.weight}，缺失时 SQL 侧已取 1.0） */
    private Double weight;

    /** 来源文件路径列表 JSON 数组文本（图行 {@code properties.filePaths}，由仓储层宽容解析为列表） */
    private String filePathsRaw;

    /** 图行创建时间（列级 {@code created_at}） */
    private OffsetDateTime createdAt;

    /** 图行缺失标志（LEFT JOIN 未命中，true 表示该命中无图谱属性） */
    private Boolean graphMissing;
}

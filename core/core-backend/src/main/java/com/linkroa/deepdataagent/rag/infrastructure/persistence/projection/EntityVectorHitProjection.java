package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 实体路向量命中投影（只读检索结果承载，对应 ①）。
 * <p>取数架构：<b>向量表为命中与账本权威</b>（{@code score} 为余弦相似度
 * {@code 1 - 余弦距离}，越高越相关；{@code chunkIdsRaw} 为单列 COALESCE 后的账本 JSON
 * 数组文本——向量表 {@code chunk_ids} 非空数组优先，空数组回落图行
 * {@code properties.sourceIds} 整数组）；<b>图表为展示权威</b>（{@code entityType}/
 * {@code description}/{@code filePathsRaw} 取图行 {@code properties} 文本，{@code createdAt}
 * 取图行列值）。</p>
 * <p>图行经 {@code (kb_id, entity_name)} 唯一索引 1:1 点查 LEFT JOIN 取得；
 * {@code graphMissing} 为真表示该向量命中在图表无对应行（摄入收敛中间态），
 * 由仓储层记录 WARN 并剔除该命中。JSON 数组文本一律由仓储层经
 * {@code RagGraphPersistenceConvert#jsonToLongList} 消化。</p>
 */
@Data
public class EntityVectorHitProjection {

    /** 命中实体名称 */
    private String entityName;

    /** 余弦相似度得分（1 - 余弦距离） */
    private Double score;

    /** chunk 账本 JSON 数组文本（向量表账本，空数组时已回落图行 sourceIds） */
    private String chunkIdsRaw;

    /** 实体类型（图行 {@code properties.entityType}） */
    private String entityType;

    /** 实体描述（图行 {@code properties.description}） */
    private String description;

    /** 来源文件路径列表 JSON 数组文本（图行 {@code properties.filePaths}，由仓储层宽容解析为列表） */
    private String filePathsRaw;

    /** 图行创建时间（列级 {@code created_at}） */
    private OffsetDateTime createdAt;

    /** 图行缺失标志（LEFT JOIN 未命中，true 表示该命中无图谱属性） */
    private Boolean graphMissing;
}

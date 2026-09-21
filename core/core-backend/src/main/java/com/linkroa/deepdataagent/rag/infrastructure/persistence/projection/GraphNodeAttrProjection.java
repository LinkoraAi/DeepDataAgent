package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 实体节点属性回查投影（只读检索结果承载，对应《realign-rag-retrieval-with-lightrag》2.6b）。
 * <p>用于「仅由关系路端点贡献、未被实体路命中」的裸名实体按名批量回查 {@code entity_node_graph}
 * 展示属性（对齐参考 §4.3⑤ {@code get_nodes_batch} 语义），使其也能以富记录进图谱视图，
 * 而非仅带名称。与实体路向量命中投影（{@link EntityVectorHitProjection}）不同：本投影<b>无</b>
 * 相似度分数与 {@code graph_missing} 标志——命中与否由「该名列是否出现在结果中」表达，
 * 分数由上层以端点所属关系相似度补上。</p>
 * <p>展示属性取图行 {@code properties}（{@code entityType}/{@code description}/{@code filePathsRaw}）
 * 与列级 {@code created_at}；{@code sourceIdsRaw} 为 {@code properties.sourceIds} 的
 * <b>JSON 数组文本</b>（该节点账本，由仓储层经
 * {@code RagGraphPersistenceConvert#jsonToLongListQuietly} 宽容消化）。</p>
 *
 * @see com.linkroa.deepdataagent.rag.domain.model.EntityHit
 */
@Data
public class GraphNodeAttrProjection {

    /** 实体名称（回查键） */
    private String entityName;

    /** 实体类型（图行 {@code properties.entityType}，可为 null） */
    private String entityType;

    /** 实体描述（图行 {@code properties.description}，可为 null） */
    private String description;

    /** 来源文件路径列表 JSON 数组文本（图行 {@code properties.filePaths}，可为 null，由仓储层宽容解析为列表） */
    private String filePathsRaw;

    /** 图行创建时间（列级 {@code created_at}，可为 null） */
    private OffsetDateTime createdAt;

    /** 节点账本 JSON 数组文本（图行 {@code properties.sourceIds}，可为 null） */
    private String sourceIdsRaw;
}

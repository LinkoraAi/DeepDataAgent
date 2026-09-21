package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.model.ChunkText;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.GraphEdgeHit;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;

import java.util.List;
import java.util.Map;

/**
 * 检索侧只读仓储端口（聚合三路召回所需 SQL，向量参数用 {@code float[]}）。
 * <p>实体/关系向量命中与 1 跳关联边走图谱四表（rag 自持只读 Mapper）；
 * {@code chunksOf} 经 knowledgebase 只读通道回取 chunk 正文/文件名，
 * 不直接操作对方 Mapper（零反向依赖）。本端口只读，不提供任何写能力。</p>
 *
 * <p>命中记录均为携带图谱属性的结构化值对象：展示属性以图表为权威、命中分数与 chunk 账本
 * 以向量表为权威（账本为空时由 SQL 侧单列 COALESCE 回落图表 {@code properties.sourceIds}
 * 整数组）。图表 1:1 行不存在的命中由实现侧记录 WARN 并剔除，不出现在返回列表中。</p>
 */
public interface RetrievalRepository {

    /**
     * 实体路向量命中：按余弦距离阈值从 {@code entity_info_vector} 召回实体，
     * 并携带 {@code entity_node_graph} 的实体类型/描述/来源文件路径/创建时间。
     *
     * @param kbId      所属知识库ID（单库隔离）
     * @param vec       查询向量（低层关键词 ll 的向量，固定下标解包产物）
     * @param threshold 余弦距离阈值（命中条件为距离小于该值）
     * @param limit     返回上限（实体路 top_k）
     * @return 实体命中列表（按向量相似度升序距离排序，图行缺失者已剔除）
     */
    List<EntityHit> searchEntities(Long kbId, float[] vec, double threshold, int limit);

    /**
     * 关系路向量命中：按余弦距离阈值从 {@code relation_info_vector} 召回关系，
     * 并携带 {@code relation_edge_graph} 的描述/关键词/权重/来源文件路径/创建时间。
     *
     * @param kbId      所属知识库ID（单库隔离）
     * @param vec       查询向量（高层关键词 hl 的向量，固定下标解包产物）
     * @param threshold 余弦距离阈值（命中条件为距离小于该值）
     * @param limit     返回上限（关系路 top_k）
     * @return 关系命中列表（保持向量相似度顺序，端点为字典序归一对，图行缺失者已剔除）
     */
    List<RelationHit> searchRelations(Long kbId, float[] vec, double threshold, int limit);

    /**
     * 1 跳关联边查询：对实体候选集批量取关联边，按「度数 + 权重」双键降序（③）。
     * <p>端点对经 SQL 侧 {@code LEAST/GREATEST} 无向归一，历史双向残留行按归一对折叠为
     * {@code updated_at} 最新一行的属性（同一归一对至多一条）；边记录自带 chunk 账本，
     * 消费方不得再依赖关系路命中做内存反查。</p>
     *
     * @param kbId        所属知识库ID（单库隔离）
     * @param entityNames 实体候选集名称列表（source/target 任一端命中即返回）
     * @param limit       返回上限（关联边 Top，默认 {@code RetrievalConstants.GRAPH_EDGE_TOP} = 50）
     * @return 关联边记录列表（无向归一 + 归一对去重，度数+权重双键降序）
     */
    List<GraphEdgeHit> relatedEdges(Long kbId, List<String> entityNames, int limit);

    /**
     * 端点实体属性批量回查（2.6b）：按名称集合点查 {@code entity_node_graph} 的展示属性与节点账本，
     * 供「仅由关系路端点贡献、未被实体路命中」的裸名实体补齐类型/描述/来源文件/创建时间。
     * <p>返回的 {@link EntityHit} 仅承载图表属性（{@code entityType}/{@code description}/
     * {@code filePaths}/{@code createdAt}）与由 {@code properties.sourceIds} 整数组解析的
     * {@code chunkIds}；{@code score} 无意义（恒 0，相似度由上层以端点所属关系命中分数补上）。
     * 图行不存在的名称不出现在结果 Map 中，由上层保持裸名降级。</p>
     * <p>实现侧对空/{@code null} 名称集合短路返回空 Map，不发起查询（零缺口零往返）。</p>
     *
     * @param kbId  所属知识库ID（单库隔离）
     * @param names 待回查实体名列表（可空/为空，此时不查询直接返回空 Map）
     * @return 名称 → 属性记录映射（仅含图行存在的名称；账本经检索侧宽容解析）
     */
    Map<String, EntityHit> findEntityAttributes(Long kbId, List<String> names);

    /**
     * 按 chunkIds 批量回取 chunk 正文与文件名（经 knowledgebase 只读通道）。
     *
     * @param kbId     所属知识库ID（单库隔离）
     * @param chunkIds chunk 标识集合
     * @return chunkId 到正文快照的映射（未命中的 chunk 不出现）
     */
    Map<Long, ChunkText> chunksOf(Long kbId, List<Long> chunkIds);
}

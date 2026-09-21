package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.rag.domain.model.ChunkText;
import com.linkroa.deepdataagent.rag.domain.model.EntityHit;
import com.linkroa.deepdataagent.rag.domain.model.GraphEdgeHit;
import com.linkroa.deepdataagent.rag.domain.model.RelationHit;
import com.linkroa.deepdataagent.rag.domain.repository.RetrievalRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityNodeGraphMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationEdgeGraphMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.EntityVectorHitProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphEdgeProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.GraphNodeAttrProjection;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationVectorHitProjection;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索侧只读仓储的关系型实现（{@link RetrievalRepository} 端口，GRAPH 通道）。
 * <p>实体/关系向量命中与 1 跳关联边走 rag 自持图谱四表只读 Mapper（SQL 全部落位 Mapper 注解，
 * 本类不拼接任何含 {@code <=>} 的语句）；chunk 正文回取经 {@link KnowledgeBaseApi} 只读通道
 * （零反向依赖，不注入 knowledgebase Mapper）。{@code float[]} 查询向量在本类转换为
 * pgvector 字面量字符串后传入 Mapper 做 {@code ::vector} 转换；相似度直取 SQL 计算列
 * {@code score = 1 - 余弦距离}（余弦相似度，越高越相关）。</p>
 *
 * <p><b>取数职责边界</b>（对齐《realign-rag-retrieval-with-lightrag》D-1）：三条检索 SQL 统一为
 * 「向量命中（HNSW）+ 图表 1:1 属性点查 JOIN + chunk 账本单列 COALESCE 兜底」骨架，
 * 展示属性以图表为权威、命中分数与 chunk 账本以向量表为权威。本层只做三件事：
 * ① 投影 → 值对象转换（JSON 数组文本经检索侧宽容版
 * {@link RagGraphPersistenceConvert#jsonToLongListQuietly} / {@code jsonToStringListQuietly} 消化，
 * 单行脏账本只让该命中贡献零块、不让异常穿透杀整个 GRAPH 通道）；
 * ② 图行缺失（{@code graph_missing}）命中记 WARN 并剔除——无属性可展示的记录不进视图、
 * 也不参与 chunk 展开；③ 列表 null 防御。账本兜底已在 SQL 侧完成，本层不再有
 * 「chunk_ids 空数组 → 二次查询图行取首元素」的往返（该路径随 D-1 整体删除）。</p>
 *
 * <p>另提供 {@code findEntityAttributes} 端点属性批量回查（2.6b）：为检索组装时「仅由关系路端点
 * 贡献、未被实体路命中」的裸名实体按名点查 {@code entity_node_graph}，补齐展示属性与节点账本。</p>
 */
@Repository
public class JdbcGraphSearchRepository implements RetrievalRepository {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(JdbcGraphSearchRepository.class);

    /** 兜底分数（SQL 侧 score 计算列恒非空，仅防包装类拆箱空指针） */
    private static final double DEFAULT_SCORE = 0.0D;

    /** pgvector 字面量前缀 */
    private static final String VECTOR_LITERAL_PREFIX = "[";

    /** pgvector 字面量后缀 */
    private static final String VECTOR_LITERAL_SUFFIX = "]";

    /** pgvector 字面量分量分隔符 */
    private static final String VECTOR_COMPONENT_SEPARATOR = ",";

    private final EntityInfoVectorMapper entityInfoVectorMapper;
    private final RelationInfoVectorMapper relationInfoVectorMapper;
    private final RelationEdgeGraphMapper relationEdgeGraphMapper;
    private final EntityNodeGraphMapper entityNodeGraphMapper;
    private final KnowledgeBaseApi knowledgeBaseApi;

    public JdbcGraphSearchRepository(EntityInfoVectorMapper entityInfoVectorMapper,
                                    RelationInfoVectorMapper relationInfoVectorMapper,
                                    RelationEdgeGraphMapper relationEdgeGraphMapper,
                                    EntityNodeGraphMapper entityNodeGraphMapper,
                                    KnowledgeBaseApi knowledgeBaseApi) {
        this.entityInfoVectorMapper = entityInfoVectorMapper;
        this.relationInfoVectorMapper = relationInfoVectorMapper;
        this.relationEdgeGraphMapper = relationEdgeGraphMapper;
        this.entityNodeGraphMapper = entityNodeGraphMapper;
        this.knowledgeBaseApi = knowledgeBaseApi;
    }

    /**
     * 实体路向量命中：余弦距离阈值召回，命中携带图表属性（类型/描述/来源文件/创建时间）
     * 与单列 COALESCE 后的 chunk 账本；图行缺失的命中记 WARN 并剔除。
     */
    @Override
    public List<EntityHit> searchEntities(Long kbId, float[] vec, double threshold, int limit) {
        if (ObjectUtils.isEmpty(vec) || limit <= 0) {
            return List.of();
        }
        List<EntityVectorHitProjection> rows =
                entityInfoVectorMapper.searchByCosineDistance(kbId, toVectorLiteral(vec), threshold, limit);
        if (CollectionUtils.isEmpty(rows)) {
            return List.of();
        }
        List<EntityHit> hits = new ArrayList<>(rows.size());
        for (EntityVectorHitProjection row : rows) {
            if (isGraphMissing(row.getGraphMissing())) {
                log.warn("实体命中在图表无对应行（摄入收敛中间态），已剔除: kbId={} entityName={}",
                        kbId, row.getEntityName());
                continue;
            }
            hits.add(new EntityHit(row.getEntityName(), safeScore(row.getScore()),
                    RagGraphPersistenceConvert.INSTANCE.jsonToLongListQuietly(row.getChunkIdsRaw()),
                    row.getEntityType(), row.getDescription(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getFilePathsRaw()),
                    row.getCreatedAt()));
        }
        return hits;
    }

    /**
     * 关系路向量命中：余弦距离阈值召回并保持向量相似度顺序，端点由向量行原生双字段承载
     * （字典序归一对，不再有「源-目标」拼接串）；展示属性取图行，图行缺失记 WARN 并剔除。
     */
    @Override
    public List<RelationHit> searchRelations(Long kbId, float[] vec, double threshold, int limit) {
        if (ObjectUtils.isEmpty(vec) || limit <= 0) {
            return List.of();
        }
        List<RelationVectorHitProjection> rows =
                relationInfoVectorMapper.searchByCosineDistance(kbId, toVectorLiteral(vec), threshold, limit);
        if (CollectionUtils.isEmpty(rows)) {
            return List.of();
        }
        List<RelationHit> hits = new ArrayList<>(rows.size());
        for (RelationVectorHitProjection row : rows) {
            if (isGraphMissing(row.getGraphMissing())) {
                log.warn("关系命中在图表无对应行（摄入收敛中间态），已剔除: kbId={} sourceName={} targetName={}",
                        kbId, row.getSourceName(), row.getTargetName());
                continue;
            }
            hits.add(new RelationHit(row.getSourceName(), row.getTargetName(), safeScore(row.getScore()),
                    RagGraphPersistenceConvert.INSTANCE.jsonToLongListQuietly(row.getChunkIdsRaw()),
                    row.getDescription(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getKeywordsRaw()),
                    row.getWeight(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getFilePathsRaw()),
                    row.getCreatedAt()));
        }
        return hits;
    }

    /**
     * 1 跳关联边查询：实体候选集经度数 CTE Mapper 按「度数 + 权重」双键降序取边，
     * 端点对已由 SQL 侧 LEAST/GREATEST 无向归一并按归一对折叠历史双向残留行（取最新一行属性），
     * 本层仅做投影 → 值对象转换（边记录自带 chunk 账本，无需回查关系向量）。
     */
    @Override
    public List<GraphEdgeHit> relatedEdges(Long kbId, List<String> entityNames, int limit) {
        if (CollectionUtils.isEmpty(entityNames) || limit <= 0) {
            return List.of();
        }
        List<GraphEdgeProjection> rows =
                relationEdgeGraphMapper.searchRelatedEdgesByDegree(kbId, entityNames, limit);
        if (CollectionUtils.isEmpty(rows)) {
            return List.of();
        }
        List<GraphEdgeHit> edges = new ArrayList<>(rows.size());
        for (GraphEdgeProjection row : rows) {
            edges.add(new GraphEdgeHit(row.getSourceName(), row.getTargetName(),
                    safeDegree(row.getEdgeDegree()), row.getWeight(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToLongListQuietly(row.getChunkIdsRaw()),
                    row.getDescription(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getKeywordsRaw()),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getFilePathsRaw()),
                    row.getCreatedAt()));
        }
        return edges;
    }

    /**
     * 端点实体属性批量回查（2.6b）：按名点查 {@code entity_node_graph} 补齐裸名端点的展示属性与
     * 节点账本；空/{@code null} 名称集合短路返回空 Map（零缺口零往返）。返回记录的 {@code score}
     * 恒为 0（相似度由上层以端点所属关系命中分数补上），图行不存在的名称不出现在结果中。
     */
    @Override
    public Map<String, EntityHit> findEntityAttributes(Long kbId, List<String> names) {
        if (CollectionUtils.isEmpty(names)) {
            return Map.of();
        }
        List<GraphNodeAttrProjection> rows = entityNodeGraphMapper.selectByKbIdAndNames(kbId, names);
        if (CollectionUtils.isEmpty(rows)) {
            return Map.of();
        }
        Map<String, EntityHit> attributes = new HashMap<>(rows.size());
        for (GraphNodeAttrProjection row : rows) {
            if (ObjectUtils.isEmpty(row) || StringUtils.isBlank(row.getEntityName())) {
                continue;
            }
            attributes.put(row.getEntityName(), new EntityHit(row.getEntityName(), DEFAULT_SCORE,
                    RagGraphPersistenceConvert.INSTANCE.jsonToLongListQuietly(row.getSourceIdsRaw()),
                    row.getEntityType(), row.getDescription(),
                    RagGraphPersistenceConvert.INSTANCE.jsonToStringListQuietly(row.getFilePathsRaw()),
                    row.getCreatedAt()));
        }
        return attributes;
    }

    /**
     * chunk 正文批量回取：经 {@link KnowledgeBaseApi#findChunksByKbIdAndChunkIds} 只读通道
     * （零反向依赖），kbId 等值过滤保证单库隔离；未命中的 chunk 不出现在结果 Map 中。
     */
    @Override
    public Map<Long, ChunkText> chunksOf(Long kbId, List<Long> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return Map.of();
        }
        List<Chunk> chunks = knowledgeBaseApi.findChunksByKbIdAndChunkIds(kbId, chunkIds);
        if (CollectionUtils.isEmpty(chunks)) {
            return Map.of();
        }
        Map<Long, ChunkText> result = new HashMap<>(chunks.size());
        for (Chunk chunk : chunks) {
            if (ObjectUtils.isEmpty(chunk) || ObjectUtils.isEmpty(chunk.id())) {
                continue;
            }
            result.put(chunk.id(), new ChunkText(chunk.id(), chunk.chunkContent(), chunk.sourceFileName()));
        }
        return result;
    }

    /**
     * 图行缺失标志取值（SQL 侧 {@code xxx IS NULL} 布尔列，null 视为不缺省）。
     *
     * @param graphMissing 投影标志位，可为 null
     * @return true 表示该命中的图表 1:1 行不存在
     */
    private static boolean isGraphMissing(Boolean graphMissing) {
        return Boolean.TRUE.equals(graphMissing);
    }

    /**
     * float[] 查询向量转 pgvector 字面量（形如 {@code "[0.1,0.2,0]"}，SQL 侧 {@code ::vector} 转换）。
     * <p>分量经 {@link BigDecimal} 定点展开：{@code BigDecimal.valueOf(float)} 取 float 的精确十进制值，
     * {@code stripTrailingZeros()} 去尾零、{@code toPlainString()} 禁用科学计数法
     * （{@code Float.toString} 会产生 {@code 1.0E-7} 类形式，pgvector 字面量解析不接受），
     * 与摄入侧 {@code ChunkPersistenceService#formatVectorLiteral} 同口径，无额外精度损失。</p>
     *
     * @param vector 非空查询向量（调用方前置判空）
     * @return pgvector 字面量字符串
     */
    private static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8);
        literal.append(VECTOR_LITERAL_PREFIX);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(VECTOR_COMPONENT_SEPARATOR);
            }
            literal.append(BigDecimal.valueOf(vector[i]).stripTrailingZeros().toPlainString());
        }
        return literal.append(VECTOR_LITERAL_SUFFIX).toString();
    }

    /**
     * 投影分数空值防御取值。
     * <p>SQL 侧 {@code 1 - (content_vector <=> 查询向量)} 计算列恒非空，此处仅防
     * {@code Double} 拆箱空指针（开发规范【强制】包装类比较使用 ObjectUtils）。</p>
     *
     * @param score 投影分数，可为 null
     * @return 余弦相似度分数；null 返回 {@link #DEFAULT_SCORE}
     */
    private static double safeScore(Double score) {
        return ObjectUtils.isEmpty(score) ? DEFAULT_SCORE : score;
    }

    /**
     * 投影边度数空值防御取值（SQL 侧已 {@code COALESCE} 兜 0，此处仅防拆箱空指针）。
     *
     * @param edgeDegree 投影度数，可为 null
     * @return 边度数；null 返回 0
     */
    private static int safeDegree(Integer edgeDegree) {
        return ObjectUtils.isEmpty(edgeDegree) ? 0 : edgeDegree;
    }
}

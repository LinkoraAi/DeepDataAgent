package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 关系命中值对象（GRAPH 通道关系视图的统一承载：关系路向量命中与 1 跳关联边转换记录同形）。
 * <p>端点为原生双字段，不再以「源-目标」拼接串承载（该形态在实体名含 {@code -} 时拆分错位）。
 * 展示属性权威为 {@code relation_edge_graph} 图行，命中分数与 chunk 账本权威为
 * {@code relation_info_vector} 向量行；图行缺失的命中由仓储层 WARN 剔除。
 * 向量表落库方向为字典序归一，图表保留原始方向，故本值对象的端点分量随来源而异
 * （关系路命中为字典序对，边转换记录为 SQL 侧 {@code LEAST/GREATEST} 归一对），
 * 等值判断一律走 {@link #normalizedPair()}。</p>
 *
 * @param sourceName 源实体名称
 * @param targetName 目标实体名称
 * @param score      通道内分数：关系路命中为向量相似度（余弦距离换算，越高越相关）；
 *                   关联边转换记录为边权重（口径见
 *                   {@code DefaultRecallService#toRelationView}，仅影响 GRAPH 通道内
 *                   WEIGHTED_SUM 的 min-max 归一，RRF 只消费排名故无感）
 * @param chunkIds   关联 chunk 账本（向量表 {@code chunk_ids} 权威，空账本回退图行
 *                   {@code properties.sourceIds} 整数组；两源皆空为空列表）
 * @param description 关系描述（图行 {@code properties.description}，可为 null）
 * @param keywords   关系关键词（图行 {@code properties.keywords}，可为空列表）
 * @param weight     关系权重（图行 {@code properties.weight}，缺失归一 {@link #DEFAULT_WEIGHT}）
 * @param filePaths  来源文件路径列表（图行 {@code properties.filePaths}，去重保序；
 *                   上下文渲染时拼接为单个字符串，MUST NOT 参与向量内容与计重）
 * @param createdAt  图行创建时间（列级 {@code created_at}，可为 null）
 */
public record RelationHit(
        String sourceName,
        String targetName,
        double score,
        List<Long> chunkIds,
        String description,
        List<String> keywords,
        Double weight,
        List<String> filePaths,
        OffsetDateTime createdAt
) {

    /** 边权重缺省值（图行未落 {@code weight} 时按普通边 1.0 计，与摄入侧默认权重同值） */
    public static final double DEFAULT_WEIGHT = 1.0D;

    /**
     * 紧凑构造器：列表分量 null 归一（并剔除空元素）、权重缺失归一 {@link #DEFAULT_WEIGHT}；
     * 文本与时间分量维持 null 容忍（渲染侧按空串落）。
     */
    public RelationHit {
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of()
                : chunkIds.stream().filter(ObjectUtils::isNotEmpty).toList();
        keywords = ObjectUtils.isEmpty(keywords) ? List.of()
                : keywords.stream().filter(StringUtils::isNotBlank).toList();
        filePaths = ObjectUtils.isEmpty(filePaths) ? List.of()
                : filePaths.stream().filter(StringUtils::isNotBlank).toList();
        weight = ObjectUtils.isEmpty(weight) ? DEFAULT_WEIGHT : weight;
    }

    /**
     * 无向归一端点对：字典序较小者为 first，{@code (a,b)} 与 {@code (b,a)} 返回同一对，
     * 作为关系视图去重键（与 {@link RelationEdge#normalizedPair()} 同口径）。
     *
     * @return 长度 2 的有序端点列表；任一端点为空时返回空列表（不可用作去重键）
     */
    public List<String> normalizedPair() {
        if (StringUtils.isBlank(sourceName) || StringUtils.isBlank(targetName)) {
            return List.of();
        }
        return sourceName.compareTo(targetName) <= 0
                ? List.of(sourceName, targetName)
                : List.of(targetName, sourceName);
    }
}

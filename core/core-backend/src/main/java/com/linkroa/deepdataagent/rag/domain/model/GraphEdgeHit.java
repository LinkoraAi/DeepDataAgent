package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1 跳关联边命中值对象（GRAPH 通道关联边查询产物，携带图属性与 chunk 账本）。
 * <p>端点对由 SQL 侧 {@code LEAST/GREATEST} 无向归一（字典序，{@code sourceName <= targetName}），
 * 历史双向残留行已在 SQL 侧经 {@code DISTINCT ON (归一端点对)} + {@code updated_at DESC}
 * 折叠为最新一行，故同一归一对至多一条记录。边记录自带账本 chunk 与属性，
 * 不再依赖本次关系路命中做内存反查；并入关系视图时转换为
 * {@link RelationHit}（口径见 {@code DefaultRecallService}）。</p>
 *
 * @param sourceName 源端点实体名（无向归一后的字典序较小者）
 * @param targetName 目标端点实体名（无向归一后的字典序较大者）
 * @param edgeDegree 边度数（两端节点度数之和，实时现算，仅作通道内排序键）
 * @param weight     边权重（图行 {@code properties.weight}，缺失归一 {@link RelationHit#DEFAULT_WEIGHT}；
 *                   仅用于选边排序与 {@link RelationHit#weight()} 展示，MUST NOT 作为通道分数）
 * @param vectorSimilarity 关系向量余弦分（{@code 1 - (content_vector <=> hl查询向量)}，可空）。
 *                   {@code null} 表示 hl 为空 / 无向量行 / {@code content_vector} 为 {@code NULL}，
 *                   由服务层回退端点实体余弦；MUST NOT 回落 {@code weight}
 * @param chunkIds   关联 chunk 账本（{@code relation_info_vector.chunk_ids} 权威，
 *                   空账本回退边行 {@code properties.sourceIds} 整数组；两源皆空为空列表）
 * @param description 边描述（图行 {@code properties.description}，可为 null）
 * @param keywords   边关键词（图行 {@code properties.keywords}，可为空列表）
 * @param filePaths  来源文件路径列表（图行 {@code properties.filePaths}，去重保序；
 *                   上下文渲染时拼接为单个字符串，MUST NOT 参与向量内容与计重）
 * @param createdAt  图行创建时间（列级 {@code created_at}，可为 null）
 */
public record GraphEdgeHit(
        String sourceName,
        String targetName,
        int edgeDegree,
        Double weight,
        Double vectorSimilarity,
        List<Long> chunkIds,
        String description,
        List<String> keywords,
        List<String> filePaths,
        OffsetDateTime createdAt
) {

    /**
     * 紧凑构造器：列表分量 null 归一（并剔除空元素）、权重缺失归一
     * {@link RelationHit#DEFAULT_WEIGHT}；文本与时间分量维持 null 容忍（渲染侧按空串落）。
     * <p>{@code vectorSimilarity} <b>不做兜底</b>：{@code null} 即“向量不可用”语义，
     * 由服务层按端点余弦回退，禁止在此回填 {@code weight}。</p>
     */
    public GraphEdgeHit {
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of()
                : chunkIds.stream().filter(ObjectUtils::isNotEmpty).toList();
        keywords = ObjectUtils.isEmpty(keywords) ? List.of()
                : keywords.stream().filter(StringUtils::isNotBlank).toList();
        filePaths = ObjectUtils.isEmpty(filePaths) ? List.of()
                : filePaths.stream().filter(StringUtils::isNotBlank).toList();
        weight = ObjectUtils.isEmpty(weight) ? RelationHit.DEFAULT_WEIGHT : weight;
    }

    /**
     * 无向归一端点对（SQL 侧已归一，此处与 {@link RelationHit#normalizedPair()} 同口径，
     * 供关系视图按归一对去重合并）。
     *
     * @return 长度 2 的有序端点列表；任一端点为空白时返回空列表（不可用作去重键）
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

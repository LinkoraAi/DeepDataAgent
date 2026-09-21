package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 实体路向量命中值对象（GRAPH 通道实体路，携带图谱属性）。
 * <p>命中与账本权威为 {@code entity_info_vector}（相似度分数、chunk 账本），
 * 展示属性（类型/描述/来源文件路径/创建时间）权威为 1:1 的
 * {@code entity_node_graph} 图行；图行缺失的命中由仓储层 WARN 剔除，不会流到本值对象。
 * 仅由关系路端点并入实体候选集、未被实体路命中的实体，其属性经
 * {@code DefaultRecallService} 的批量回查（{@code findEntityAttributes}）以图行为准补齐
 * （分数取其来源关系命中相似度）；回查不到图行的收敛中间态才以「只有名称 + 来源关系相似度」
 * 的降级形态出现（其余属性为空）。</p>
 *
 * @param name        实体名称
 * @param score       来源向量相似度（余弦距离换算，越高越相关；端点降级来源取所属关系相似度）
 * @param chunkIds    关联 chunk 账本（向量表 {@code chunk_ids} 权威，空账本回退图行
 *                    {@code properties.sourceIds} 整数组；两源皆空为空列表）
 * @param entityType  实体类型（图行 {@code properties.entityType}，可为 null）
 * @param description 实体描述（图行 {@code properties.description}，可为 null）
 * @param filePaths   来源文件路径列表（图行 {@code properties.filePaths}，去重保序；
 *                    上下文渲染时拼接为单个字符串，MUST NOT 参与向量内容与计重）
 * @param createdAt   图行创建时间（列级 {@code created_at}，可为 null）
 */
public record EntityHit(
        String name,
        double score,
        List<Long> chunkIds,
        String entityType,
        String description,
        List<String> filePaths,
        OffsetDateTime createdAt
) {

    /**
     * 紧凑构造器：chunk 账本与来源文件路径列表做 null 归一（空/含空元素一律归一为不可变干净列表），
     * 文本与时间分量维持 null 容忍（渲染侧按空串落）。
     */
    public EntityHit {
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of()
                : chunkIds.stream().filter(ObjectUtils::isNotEmpty).toList();
        filePaths = ObjectUtils.isEmpty(filePaths) ? List.of()
                : filePaths.stream().filter(StringUtils::isNotBlank).toList();
    }
}

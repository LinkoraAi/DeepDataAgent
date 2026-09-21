package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 关系条目「账本 + 当前属性」快照值对象（重建分类阶段的读取载体，design D3 步骤②）。
 * <p>端点对在仓储实现侧已按字典序归一（与 {@link RelationEdge#normalizedPair()} 口径一致），
 * 携带向量表 {@code chunk_ids} 账本（权威）、当前向量内容/向量（复用旧向量判定）与图行
 * {@code properties}（降级保留语义字段的原值来源）。图行缺失（收敛中间态）时属性归一为空。</p>
 * <p>本快照为<b>不加锁普通读</b>产物，MUST NOT 作为写回基准（同 {@link EntityLedgerSnapshot}）。</p>
 *
 * @param kbId       所属知识库ID
 * @param sourceName 归一端点对之源（字典序较小）
 * @param targetName 归一端点对之目标（字典序较大）
 * @param chunkIds   当前向量账本（账本权威）
 * @param content    向量行当前内容（可为 null）
 * @param vector     向量行当前向量（可为 null）
 * @param properties 图行当前属性（图行缺失时为空属性，非 null）
 * @author DeepDataAgent
 */
public record RelationLedgerSnapshot(
        Long kbId,
        String sourceName,
        String targetName,
        List<Long> chunkIds,
        String content,
        float[] vector,
        RelationProperties properties) {

    /**
     * 紧凑构造器：不变量校验与空值归一；端点对自构造起恒为字典序归一形态
     * （历史反向向量行在仓储实现侧归一后进入本模型，下游不再关心方向）。
     */
    public RelationLedgerSnapshot {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("关系账本快照必须关联知识库");
        }
        if (StringUtils.isBlank(sourceName) || StringUtils.isBlank(targetName)
                || StringUtils.equals(sourceName, targetName)) {
            throw new IllegalArgumentException("关系账本快照端点非法（空或自环）");
        }
        if (sourceName.compareTo(targetName) > 0) {
            String swapped = sourceName;
            sourceName = targetName;
            targetName = swapped;
        }
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of() : List.copyOf(chunkIds);
        properties = ObjectUtils.isEmpty(properties) ? RelationProperties.empty() : properties;
    }

    /**
     * 无向归一端点对（字典序），与 {@link RelationEdge#normalizedPair()} 同口径。
     *
     * @return 长度 2 的有序端点列表
     */
    public List<String> normalizedPair() {
        return List.of(sourceName, targetName);
    }
}

package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * 实体抽取结果值对象（单文档全量 chunk 抽取聚合后的片段集合）。
 * <p>nodes/edges 已完成 chunk 内与跨 chunk 的文档级聚合（同名实体、同无向端点对关系归一，
 * 描述取长、关键词并集、跨块权重累加），可直接作为
 * {@link GraphMergeService#mergeNodesAndEdges} 的入参喂给图合并阶段。</p>
 *
 * @param nodes 抽取出的实体节点列表（含多模态 sidecar 主实体，null 归一为空列表）
 * @param edges 抽取出的关系边列表（含 belongs_to sidecar 边，null 归一为空列表）
 * @author DeepDataAgent
 */
public record EntityExtractionResult(List<EntityNode> nodes, List<RelationEdge> edges) {

    /**
     * 紧凑构造器：空值归一为不可变空列表。
     */
    public EntityExtractionResult {
        nodes = ObjectUtils.isEmpty(nodes) ? List.of() : List.copyOf(nodes);
        edges = ObjectUtils.isEmpty(edges) ? List.of() : List.copyOf(edges);
    }

    /**
     * 空结果工厂（无 chunk 或全部 chunk 抽取失败时返回）。
     *
     * @return 空抽取结果
     */
    public static EntityExtractionResult empty() {
        return new EntityExtractionResult(List.of(), List.of());
    }
}

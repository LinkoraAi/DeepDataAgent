package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.RetrievalCandidate;

import java.util.List;

/**
 * 召回服务（Stage 1：VECTOR / BM25 / GRAPH 三路召回）。
 * <p>MIX 三路串行召回（虚拟线程仅用于摄入侧并发，召回三通道不并行化；
 * 批量预计算 embedding 按固定下标解包）；NAIVE 走向量+全文双通道；
 * 图谱空 → GRAPH 通道 MISSING（不参与融合，VECTOR/BM25 不受影响）；
 * 全部结果携带通道标记与通道内分数（由复合结果端口承接）。</p>
 *
 * <p><b>最小扩展</b>：返回值由候选列表扩为复合结果
 * {@link RecallOutcome}——Stage 4 {@code ContextBuilder.build(chunks, kg, budget)}
 * 所需结构化图谱结果 {@link KgSearchResult} 仅能在图谱召回过程中产生，
 * 原接口签名未承接该数据流，编排层无法二次获取（避免重复向量查询）。</p>
 */
public interface RecallService {

    /**
     * 执行召回，返回带通道标记的候选集与结构化图谱结果。
     *
     * @param q   检索内部执行请求（kbId 单库隔离、改写后 query 与关键词均由编排层传入）
     * @param cfg 检索策略配置（strategyType 决定 MIX/NAIVE 通道组合）
     * @return 召回复合结果（候选列表含通道与分数标记，可为空；NAIVE 或图谱 MISSING 时
     *         图谱结果为空集合的 {@link KgSearchResult}）
     */
    RecallOutcome recall(RetrievalQuery q, RetrievalStrategyConfig cfg);

    /**
     * 召回复合结果：候选集 + 结构化图谱检索结果（供下游融合与上下文构建分别消费）。
     *
     * @param candidates 召回候选列表（含通道与分数标记，可为空）
     * @param kgResult   结构化图谱结果（实体候选集 / 关系视图 / 有序 chunk，均为携带图谱属性的
     *                   结构化记录；NAIVE 与 MISSING 时各字段为空列表）
     */
    record RecallOutcome(
            List<RetrievalCandidate> candidates,
            KgSearchResult kgResult
    ) {
    }
}
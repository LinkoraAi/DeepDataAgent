package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 答案生成服务（Stage 5：MIX/NAIVE 模板 + LLM 答案缓存 + 兜底）。
 * <p>MIX → {@code rag_response}、NAIVE → {@code naive_rag_response}；
 * user_query = 改写后 query；答案缓存 {@code cache_type=ANSWER}（键按要素串 MD5），
 * 命中直返未命中生成后回写；{@code fail_response} 兜底直出
 * （关键词兜底失败 / resultChunkCount=0 / 图谱空且向量+全文空）。</p>
 */
public interface AnswerGenerator {

    /**
     * 生成最终答案（包含缓存读写与兜底降级）。
     *
     * @param query   用户查询文本（改写后 query）
     * @param context 上下文产物（contextData / referenceList / kgResult）
     * @param cfg     检索策略配置（strategyType 决定模板选择）
     * @param q       检索内部执行请求（缓存键要素与降级判断来源）
     * @return 答案文本（失败时可为 {@code fail_response} 字面量）
     */
    String generate(String query, KgContext context, RetrievalStrategyConfig cfg, RetrievalQuery q);

    /**
     * 生成最终答案（带 LLM 缓存命中计数口径）。
     * <p>语义与 {@link #generate(String, KgContext, RetrievalStrategyConfig, RetrievalQuery)} 完全一致，
     * 额外在 {@code ANSWER} 缓存命中时对计数器递增一次，
     * 供检索编排收尾摘要日志消费。</p>
     *
     * @param query     用户查询文本（改写后 query）
     * @param context   上下文产物（contextData / referenceList / kgResult）
     * @param cfg       检索策略配置（strategyType 决定模板选择）
     * @param q         检索内部执行请求（缓存键要素与降级判断来源）
     * @param cacheHits LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 答案文本（失败时可为 {@code fail_response} 字面量）
     */
    String generate(String query, KgContext context, RetrievalStrategyConfig cfg, RetrievalQuery q,
                    AtomicInteger cacheHits);
}
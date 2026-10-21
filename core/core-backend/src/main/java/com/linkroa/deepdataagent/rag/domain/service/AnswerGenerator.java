package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import reactor.core.publisher.Flux;

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

    /**
     * 流式生成最终答案（增量输出）。
     * <p>语义与 {@link #generate(String, KgContext, RetrievalStrategyConfig, RetrievalQuery, AtomicInteger)}
     * 等价：降级前置校验、模板渲染、缓存读写与兜底口径完全一致，区别仅在于不对模型增量做聚合，
     * 而是按到达顺序逐段下发。同步方法即本方法的聚合形态，二者 MUST 产出相同文本。</p>
     * <p><b>缓存语义</b>：命中时不发起模型调用，直接产出一次全文；未命中时在流正常完成且已累积
     * 非空文本的前提下回写缓存。订阅被取消（客户端断开）时 MUST NOT 回写缓存。</p>
     * <p><b>错误语义</b>：模型调用失败时若尚未产出任何增量，则产出一次 {@code fail_response} 兜底文本；
     * 若已产出部分增量，则将错误视为流结束并保留已产出内容（增量无法撤回）。</p>
     *
     * @param query     用户查询文本（改写后 query）
     * @param context   上下文产物（contextData / referenceList / kgResult）
     * @param cfg       检索策略配置（strategyType 决定模板选择）
     * @param q         检索内部执行请求（缓存键要素与降级判断来源）
     * @param cacheHits LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 增量文本流（冷流，订阅时才真正发起模型调用）；永不为 {@code null}
     */
    Flux<String> generateStream(String query, KgContext context, RetrievalStrategyConfig cfg, RetrievalQuery q,
                                AtomicInteger cacheHits);
}
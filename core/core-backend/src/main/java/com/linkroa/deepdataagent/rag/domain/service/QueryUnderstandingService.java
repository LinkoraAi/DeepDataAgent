package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.domain.model.KeywordPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 查询理解服务（Stage 0：问题改写 + 双层关键词提取）。
 * <p>改写与提取解耦：改写开关不区分模式（MIX/NAIVE 均生效），
 * 关键词提取仅 MIX 执行 LLM；两类产物均走 {@code llm_cache}
 * （KEYWORD_EXTRACT / QUERY_REWRITE 分类）缓存回放。</p>
 *
 * <p><b>最小扩展</b>：两方法补充 {@code kbId} 参数——
 * {@code llm_cache} 复合唯一键为 {@code (kb_id, cache_type, cache_key)}，
 * 缓存读写必须携带库隔离维度，原接口签名未承接该入参。</p>
 */
public interface QueryUnderstandingService {

    /**
     * 问题改写：仅当 {@code cfg.rewriteQuestion() == true} 生效，
     * 返回改写后 query（否则原样返回）；缓存 {@code cache_type=QUERY_REWRITE}。
     *
     * @param query 用户原始问题
     * @param cfg   检索策略配置（rewriteQuestion 开关）
     * @param kbId  所属知识库ID（缓存库级隔离维度）
     * @return 改写后（或原样）的查询文本
     */
    String rewrite(String query, RetrievalStrategyConfig cfg, Long kbId);

    /**
     * 双层关键词提取：仅 MIX 执行 LLM；显式关键词
     * （hlKeywords/llKeywords 非空）优先跳过 LLM；空兜底按规则回退；
     * 缓存 {@code cache_type=KEYWORD_EXTRACT}。
     *
     * @param query                用户查询文本（改写后 query）
     * @param explicitHlKeywords   显式高层关键词（非空跳过 LLM，可空）
     * @param explicitLlKeywords   显式低层关键词（非空跳过 LLM，可空）
     * @param cfg                  检索策略配置（strategyType 决定是否触发 LLM）
     * @param kbId                 所属知识库ID（缓存库级隔离维度）
     * @return 双层关键词对（hl/ll，可为空列表，由调用方按兜底语义处理）
     */
    KeywordPair extract(String query, List<String> explicitHlKeywords, List<String> explicitLlKeywords,
                        RetrievalStrategyConfig cfg, Long kbId);

    /**
     * 问题改写（带 LLM 缓存命中计数口径）。
     * <p>语义与 {@link #rewrite(String, RetrievalStrategyConfig, Long)} 完全一致，
     * 额外在 {@code QUERY_REWRITE} 缓存命中时对计数器递增一次，
     * 供检索编排收尾摘要日志消费。</p>
     *
     * @param query     用户原始问题
     * @param cfg       检索策略配置（rewriteQuestion 开关）
     * @param kbId      所属知识库ID（缓存库级隔离维度）
     * @param cacheHits LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 改写后（或原样）的查询文本
     */
    String rewrite(String query, RetrievalStrategyConfig cfg, Long kbId, AtomicInteger cacheHits);

    /**
     * 双层关键词提取（带 LLM 缓存命中计数口径）。
     * <p>语义与 {@link #extract(String, List, List, RetrievalStrategyConfig, Long)} 完全一致，
     * 额外在 {@code KEYWORD_EXTRACT} 缓存命中时对计数器递增一次。</p>
     *
     * @param query                用户查询文本（改写后 query）
     * @param explicitHlKeywords   显式高层关键词（非空跳过 LLM，可空）
     * @param explicitLlKeywords   显式低层关键词（非空跳过 LLM，可空）
     * @param cfg                  检索策略配置（strategyType 决定是否触发 LLM）
     * @param kbId                 所属知识库ID（缓存库级隔离维度）
     * @param cacheHits            LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 双层关键词对（hl/ll，可为空列表，由调用方按兜底语义处理）
     */
    KeywordPair extract(String query, List<String> explicitHlKeywords, List<String> explicitLlKeywords,
                        RetrievalStrategyConfig cfg, Long kbId, AtomicInteger cacheHits);
}
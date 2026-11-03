package com.linkroa.deepdataagent.rag.application.contract;

import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.service.RetrievalConstants;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 检索内部执行请求值对象（无状态，一次请求对应一个策略快照，编排入口直接消费）。
 * <p>紧凑构造器将全部 Integer 预算/数量字段的 {@code null} 归一为对应默认值：
 * {@code vectorQueryCount / bm25QueryCount / graphQueryCount → DEFAULT_QUERY_COUNT}、
 * {@code maxTotalTokens / maxEntityTokens / maxRelationTokens → 各自默认常量}、
 * {@code graphEdgeTop / graphEdgeChunkLimit → RetrievalConstants 同名默认值}；
 * {@code kbId / query} 保持原样并要求非空（单库隔离与检索文本为链路硬前提）；
 * {@code images} 空值归一为不可变空列表；{@code multimodal} 空值归一为
 * {@link RetrievalMultimodalOptions#defaults()}（双开关全开，与基线行为等价）。</p>
 *
 * <p><b>通道召回数量的填充链</b>（库级配置口径，{@code add-channel-query-count-config} 变更）：
 * REST 请求体不再承载通道数量（历史 topK/chunkTopK 字段已删除，携带即忽略）；
 * 编排层在 Stage 1 加载库级 {@code retrieval_strategy.channelQueryCountConfig} 后重建
 * effective 请求时统一填充——库级未配置的字段保持默认 {@value #DEFAULT_QUERY_COUNT}。
 * 图谱数量为实体路与关系路共用的单值口径（对齐 LightRAG {@code top_k}），
 * 同时约束图谱关联 chunk 候选的入场配额（E1）。</p>
 *
 * <p><b>图谱关联边召回上限为何仍落在本契约、而非库级 {@code RetrievalStrategyConfig}</b>
 * （《realign-rag-retrieval-with-lightrag》D-8 逃生门口径）：关联边 Top 上限与每源
 * chunk 上限需要可配，但属检索内部调优参数，与面向用户的通道召回数量不同层；
 * REST 请求体不承载（控制器固定传 {@code null} 走默认值）。</p>
 *
 * @param kbId                 所属知识库ID（单库隔离维度，必填）
 * @param query                用户原始问题（必填，改写/关键词提取/召回的输入底稿）
 * @param hlKeywords           显式高层关键词（关系路方向，非空时跳过 LLM 提取，可空）
 * @param llKeywords           显式低层关键词（实体路方向，非空时跳过 LLM 提取，可空）
 * @param vectorQueryCount     向量通道召回数量（默认 {@value #DEFAULT_QUERY_COUNT}，由编排层从库级配置填充）
 * @param bm25QueryCount       BM25 通道召回数量（默认 {@value #DEFAULT_QUERY_COUNT}，由编排层从库级配置填充）
 * @param graphQueryCount      图谱通道召回数量——实体/关系路与入场配额共用单值
 *                             （默认 {@value #DEFAULT_QUERY_COUNT}，由编排层从库级配置填充）
 * @param maxTotalTokens       上下文总 token 预算（默认 {@value #DEFAULT_MAX_TOTAL_TOKENS}）
 * @param maxEntityTokens      实体上下文 token 预算（默认 {@value #DEFAULT_MAX_ENTITY_TOKENS}）
 * @param maxRelationTokens    关系上下文 token 预算（默认 {@value #DEFAULT_MAX_RELATION_TOKENS}）
 * @param images               检索附图（通路 A）：已由入参校验层校验并解码的
 *                             内联图片，<b>仅</b>供 Stage 0 前置的附图转译消费（零持久化、不进召回与作答
 *                             请求），重建 effective 请求时不再向下传递；可空，空回落不可变空列表
 * @param multimodal           多模态通路开关参数：
 *                             {@code queryImageTranscribe} 控制通路 A 是否在 Stage 0 前置消费附图
 *                             （false 时忽略附图并 WARN，按纯文本继续），{@code answerImageDirectRead}
 *                             控制通路 B 是否在作答阶段解析上下文原图直读（false 时跳过解析、
 *                             零对象存储交互）；两开关随契约下传至编排与作答生效点，
 *                             重建 effective 请求时须原样携带；可空，空回落 {@link
 *                             RetrievalMultimodalOptions#defaults()}（双 true，与基线行为等价）
 * @param graphEdgeTop         GRAPH 通道 1 跳关联边返回上限（可空，空回落
 *                             {@link RetrievalConstants#GRAPH_EDGE_TOP}；非空时必须为正整数）
 * @param graphEdgeChunkLimit  GRAPH 通道三源（实体/关系/关联边）每源 chunk 抽取上限
 *                             （可空，空回落 {@link RetrievalConstants#GRAPH_EDGE_CHUNK_LIMIT}；
 *                             非空时必须为正整数）
 */
public record RetrievalQuery(
        Long kbId,
        String query,
        List<String> hlKeywords,
        List<String> llKeywords,
        Integer vectorQueryCount,
        Integer bm25QueryCount,
        Integer graphQueryCount,
        Integer maxTotalTokens,
        Integer maxEntityTokens,
        Integer maxRelationTokens,
        List<LlmImage> images,
        RetrievalMultimodalOptions multimodal,
        Integer graphEdgeTop,
        Integer graphEdgeChunkLimit
) {

    /** 通道召回数量默认值（向量/BM25/图谱共用；库级未配置时的运行时取值） */
    public static final int DEFAULT_QUERY_COUNT = 20;

    /** 上下文总 token 预算默认值 */
    public static final int DEFAULT_MAX_TOTAL_TOKENS = 30000;

    /** 实体上下文 token 预算默认值 */
    public static final int DEFAULT_MAX_ENTITY_TOKENS = 6000;

    /** 关系上下文 token 预算默认值 */
    public static final int DEFAULT_MAX_RELATION_TOKENS = 8000;

    /**
     * 紧凑构造器：非空合理性校验 + Integer 字段 null 归默认值 + 多模态开关 null 归全开默认。
     */
    public RetrievalQuery {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("检索请求 kbId 不能为空");
        }
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("检索请求 query 不能为空");
        }
        vectorQueryCount = requirePositive(vectorQueryCount, DEFAULT_QUERY_COUNT, "vectorQueryCount");
        bm25QueryCount = requirePositive(bm25QueryCount, DEFAULT_QUERY_COUNT, "bm25QueryCount");
        graphQueryCount = requirePositive(graphQueryCount, DEFAULT_QUERY_COUNT, "graphQueryCount");
        maxTotalTokens = ObjectUtils.isEmpty(maxTotalTokens) ? DEFAULT_MAX_TOTAL_TOKENS : maxTotalTokens;
        maxEntityTokens = ObjectUtils.isEmpty(maxEntityTokens) ? DEFAULT_MAX_ENTITY_TOKENS : maxEntityTokens;
        maxRelationTokens = ObjectUtils.isEmpty(maxRelationTokens) ? DEFAULT_MAX_RELATION_TOKENS : maxRelationTokens;
        graphEdgeTop = requirePositive(graphEdgeTop, RetrievalConstants.GRAPH_EDGE_TOP, "graphEdgeTop");
        graphEdgeChunkLimit = requirePositive(graphEdgeChunkLimit,
                RetrievalConstants.GRAPH_EDGE_CHUNK_LIMIT, "graphEdgeChunkLimit");
        if (CollectionUtils.isEmpty(images)) {
            images = List.of();
        } else {
            images = List.copyOf(images);
        }
        multimodal = ObjectUtils.defaultIfNull(multimodal, RetrievalMultimodalOptions.defaults());
    }

    /**
     * 数量/上限归一：空值回落默认值，非空要求正整数（该类值直下 SQL LIMIT，非正即非法）。
     *
     * @param value      请求侧取值，可空
     * @param defaultVal 缺省回落值
     * @param field      字段名（异常定位）
     * @return 归一后的正整数
     */
    private static Integer requirePositive(Integer value, int defaultVal, String field) {
        if (ObjectUtils.isEmpty(value)) {
            return defaultVal;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("检索请求 " + field + " 必须为正整数");
        }
        return value;
    }

    /**
     * 便捷构造器：纯文本检索请求（无附图，多模态开关取默认全开，图谱上限走默认值）。
     * <p>通道召回数量恒传 {@code null}（由编排层从库级配置填充，请求方不再指定）。</p>
     *
     * @param kbId              所属知识库ID
     * @param query             用户原始问题
     * @param hlKeywords        显式高层关键词（可空）
     * @param llKeywords        显式低层关键词（可空）
     * @param maxTotalTokens    上下文总 token 预算（可空走默认）
     * @param maxEntityTokens   实体上下文 token 预算（可空走默认）
     * @param maxRelationTokens 关系上下文 token 预算（可空走默认）
     */
    public RetrievalQuery(Long kbId, String query, List<String> hlKeywords, List<String> llKeywords,
                          Integer maxTotalTokens, Integer maxEntityTokens, Integer maxRelationTokens) {
        this(kbId, query, hlKeywords, llKeywords, null, null, null, maxTotalTokens,
                maxEntityTokens, maxRelationTokens, List.of(), RetrievalMultimodalOptions.defaults(),
                null, null);
    }

    /**
     * 便捷构造器：带附图检索请求（多模态开关取默认全开，图谱上限走默认值）。
     * <p>通道召回数量恒传 {@code null}（由编排层从库级配置填充，请求方不再指定）。</p>
     *
     * @param kbId              所属知识库ID
     * @param query             用户原始问题
     * @param hlKeywords        显式高层关键词（可空）
     * @param llKeywords        显式低层关键词（可空）
     * @param maxTotalTokens    上下文总 token 预算（可空走默认）
     * @param maxEntityTokens   实体上下文 token 预算（可空走默认）
     * @param maxRelationTokens 关系上下文 token 预算（可空走默认）
     * @param images            已校验解码的检索附图（可空，空回落不可变空列表）
     */
    public RetrievalQuery(Long kbId, String query, List<String> hlKeywords, List<String> llKeywords,
                          Integer maxTotalTokens, Integer maxEntityTokens, Integer maxRelationTokens,
                          List<LlmImage> images) {
        this(kbId, query, hlKeywords, llKeywords, null, null, null, maxTotalTokens,
                maxEntityTokens, maxRelationTokens, images, RetrievalMultimodalOptions.defaults(),
                null, null);
    }
}

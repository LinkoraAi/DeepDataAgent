package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;

import java.util.List;

/**
 * 精排服务（Stage 3：库级 reranker 重排，带异常降级）。
 * <p>{@code rerankConfig.enabled=false} 直出粗排；开启时按 {@code rerankConfig.topK}（默认 50）截断候选、
 * 经 knowledgebase 只读通道回取 chunk 正文后<b>一次批量</b>调用
 * {@link com.linkroa.deepdataagent.rag.infrastructure.client.RerankClient#scoreBatch} 打分、按重排分降序、
 * 按 {@code similarThreshold} 过滤、按 {@code resultChunkCount} 截断；
 * 依赖异常/超时统一捕获记 warning 后直出粗排，不向编排层抛异常。</p>
 *
 * <p><b>最小扩展</b>（接口签名调整，原因如下）：</p>
 * <ol>
 *   <li>补充 {@code query} 参数——{@code RerankClient.score(modelProfileId, query, passage)} 逐候选打分
 *       必须携带用户查询，原接口签名缺失该入参导致无法完成打分；</li>
 *   <li>配置入参由 {@link RerankModelConfig} 扩为 {@link RetrievalStrategyConfig}——
 *       {@code similarThreshold} 过滤与 {@code resultChunkCount} 截断所需字段仅存在于策略配置
 *       （重排模型配置 {@code rerankConfig()} 为其子对象），一次传齐可避免实现侧回读库配置或追加零散参数。</li>
 * </ol>
 */
public interface Reranker {

    /**
     * 对粗排候选执行精排（默认关闭直出，开启时异常降级直出粗排）。
     *
     * @param query      用户查询文本（启用改写时为改写后 query），用于逐候选打分；空白时视为不可打分
     * @param candidates 粗排候选列表（全局有序）
     * @param cfg        检索策略配置快照（取 {@code rerankConfig} 判定开关与 topK/modelProfileId，
     *                   取 {@code similarThreshold}、{@code resultChunkCount} 做过滤与截断）；可为空
     * @param kbId       所属知识库ID（单库隔离，回取 chunk 正文时的等值过滤条件）
     * @return 精排后候选列表（未开启精排或异常降级时与入参一致）
     */
    List<RankedChunk> rerank(String query, List<RankedChunk> candidates, RetrievalStrategyConfig cfg, Long kbId);
}

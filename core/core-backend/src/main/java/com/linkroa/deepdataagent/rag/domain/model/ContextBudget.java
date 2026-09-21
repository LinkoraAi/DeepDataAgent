package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;

/**
 * 上下文构建预算值对象。
 * <p>承载预算上限与查询文本，不承担计算：chunk 可用预算
 * {@code available} 的动态计算（扣回答系统模板空算、上下文框架空算、query、
 * BUFFER_TOKENS）逻辑放在 {@code ContextBuilder} 实现内；实体/关系列表渲染前的
 * token 份额截断（P3）由 {@code ContextBuilder} 实现消费
 * {@code maxEntityTokens}/{@code maxRelationTokens} 执行，本 record 保持纯数据。</p>
 *
 * <p><b>截断份额归一口径</b>：紧凑构造器只做非正值归一——{@code maxEntityTokens} /
 * {@code maxRelationTokens} 任一 {@code <= 0}（调用方显式传 0、负数即视为未提供）时，
 * 回落 {@link RetrievalQuery} 的同名默认常量（{@link RetrievalQuery#DEFAULT_MAX_ENTITY_TOKENS} /
 * {@link RetrievalQuery#DEFAULT_MAX_RELATION_TOKENS}），与 REST 契约缺省值同源；
 * 两分量为原始 {@code int}，null 不可达，归一只按非正口径触发。
 * {@code maxTotalTokens}/{@code query} 维持原样不加工（口径与扩展前一致）。</p>
 *
 * @param maxTotalTokens    上下文总 token 预算上限
 * @param query             用户查询文本（改写后 query，参与预算扣减与模板渲染）
 * @param maxEntityTokens   实体列表 token 截断份额（渲染前逐条累加上限，计重仅按知识字段）
 * @param maxRelationTokens 关系列表 token 截断份额（渲染前逐条累加上限，计重仅按知识字段）
 */
public record ContextBudget(
        int maxTotalTokens,
        String query,
        int maxEntityTokens,
        int maxRelationTokens
) {

    /**
     * 紧凑构造器：实体/关系截断份额非正值归一为 {@link RetrievalQuery} 默认常量
     * （口径见类注释），其余分量原样保留。
     */
    public ContextBudget {
        if (maxEntityTokens <= 0) {
            maxEntityTokens = RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS;
        }
        if (maxRelationTokens <= 0) {
            maxRelationTokens = RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS;
        }
    }
}

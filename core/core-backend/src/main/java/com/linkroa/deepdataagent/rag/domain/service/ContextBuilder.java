package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContextBudget;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;

import java.util.List;

/**
 * 上下文构建服务（Stage 4：动态 token 预算下的上下文渲染）。
 * <p>{@code available} 预算 = maxTotalTokens − 回答系统模板空算(sys，与 Stage 5 同模板
 * 同默认变量、内容槽空算) − 上下文框架空算(kg，含实际实体/关系与模板骨架) − query −
 * BUFFER_TOKENS(200)，下限钳制 0；实体/关系列表渲染前分别按 ContextBudget 的
 * {@code maxEntityTokens}/{@code maxRelationTokens} 独立份额逐条累加截断
 * （计重仅按知识字段，剔除 file_path/created_at，超即停保前缀）；
 * 按预算从头累加排序后 chunk（复用 TokenCounter 真实编码），
 * 超预算即停；引用列表 ≤5 条（{@code [n] 来源文件名}）；
 * MIX/NAIVE 分别选 {@code kg_query_context} / {@code naive_query_context} 模板渲染，
 * 实体/关系记录以 ObjectMapper 逐行拼接。</p>
 */
public interface ContextBuilder {

    /**
     * 构建渲染后的上下文与结构化图谱结果。
     *
     * @param chunks 全局有序 chunk 列表（预算内截断由本方法执行）
     * @param kg     结构化实体/关系/chunk 结果（供上下文与 raw_data）
     * @param budget 上下文预算入参（maxTotalTokens、查询文本与实体/关系截断份额，
     *               available 本方法内计算）
     * @return 上下文产物（contextData / referenceList / kgResult）
     */
    KgContext build(List<RankedChunk> chunks, KgSearchResult kg, ContextBudget budget);

    /**
     * 构建渲染后的上下文与结构化图谱结果（带知识库隔离维度的完整入口，编排层必须调用本方法）。
     *
     * <p><b>最小扩展</b>：接口原三参签名未承接 chunk 正文批量回取
     * 所需的库隔离维度 {@code kbId}——回取必须按 {@code kbId} 等值过滤保证
     * 单库隔离，缺 kbId 时实现方只能跳过正文回取、上下文退化为仅图谱部分；
     * 故将带 kbId 的完整构建入口固化为接口契约。</p>
     *
     * @param kbId   chunk 正文回取所属知识库ID（单库隔离维度，编排层必填）
     * @param chunks 全局有序 chunk 列表（预算内截断由本方法执行）
     * @param kg     结构化实体/关系/chunk 结果（供上下文与 raw_data）
     * @param budget 上下文预算入参（maxTotalTokens、查询文本与实体/关系截断份额，
     *               available 本方法内计算）
     * @return 上下文产物（contextData / referenceList / kgResult）
     */
    KgContext build(Long kbId, List<RankedChunk> chunks, KgSearchResult kg, ContextBudget budget);
}
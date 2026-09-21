package com.linkroa.deepdataagent.rag.application.contract;

import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Map;

/**
 * 检索结果值对象（编排入口对外返回的统一契约）。
 * <p>{@code answer} 可为 {@code fail_response} 字面量兜底（关键词兜底失败或
 * 无可利用上下文时，不触发 LLM 直接返回）；{@code references} 与
 * {@code context.referenceList} 保持一致（{@code [n] 来源文件名}，最多 5 条）；
 * {@code rawData} 承载结构化实体/关系/chunk 原始数据（供上层展示/审计）；
 * {@code chunkViews} 承载命中切片的明细（正文 + 多模态图片引用），按上下文序，
 * 是 REST 响应 chunk 列表的投影来源；{@code degraded} 标记任一 Stage（召回到答案生成）
 * 发生降级，不影响主返回。</p>
 *
 * @param answer     最终答案文本（失败时可为 {@code fail_response} 兜底）
 * @param context    渲染后的上下文与结构化图谱结果（可为空上下文，不置 null）
 * @param references 引用列表（与 context.referenceList 一致，最多 5 条）
 * @param rawData    结构化实体/关系/chunk 原始数据（键值展开，可空）
 * @param chunkViews 命中切片明细（按上下文序，构造时空值归一为空表，永不为 null）
 * @param degraded   标记任一 Stage 降级
 */
public record RetrievalResult(
        String answer,
        KgContext context,
        List<String> references,
        Map<String, Object> rawData,
        List<RetrievalChunkView> chunkViews,
        boolean degraded
) {

    /**
     * 紧凑构造器：{@code chunkViews} 空值归一为不可变空表，保证消费方无需二次判空。
     */
    public RetrievalResult {
        chunkViews = CollectionUtils.isEmpty(chunkViews) ? List.of() : List.copyOf(chunkViews);
    }
}
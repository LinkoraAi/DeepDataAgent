package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * 上下文构建产物值对象（Stage 4 输出）。
 *
 * <p>起新增第 4 分量 {@code retainedChunkIds}，
 * 使「哪些切片真正装入了作答上下文」对下游（通路 B 直读候选解析）可见。</p>
 *
 * @param contextData      MIX/NAIVE 模板渲染后的上下文文本（kg_query_context 或 naive_query_context）
 * @param referenceList    引用列表（{@code [n] 来源文件名}，最多 5 条）
 * @param kgResult         结构化实体/关系/chunk 结果（供上游 raw_data 使用）
 * @param retainedChunkIds token 预算内<b>实际装入</b>上下文的全通道 chunkId（按上下文序，
 *                         不区分命中通道）；构造时空值归一为不可变空表并做防御性拷贝。
 *                         <b>空表承载「通路 B 候选回落图谱有序切片」语义</b>（构建退化路径与
 *                         Stage 4 异常降级路径均为空表）。新调用点必须走四参规范化构造器，
 *                         三参便捷构造器仅限退化语义（如降级路径显式表达「无装入集」），
 *                         误用会静默丢失装入集。
 */
public record KgContext(
        String contextData,
        List<String> referenceList,
        KgSearchResult kgResult,
        List<Long> retainedChunkIds
) {

    /**
     * 紧凑构造器：{@code retainedChunkIds} 空值归一为不可变空表，非空时 {@link List#copyOf}
     * 防御性拷贝，保证值对象不可变语义。
     */
    public KgContext {
        retainedChunkIds = CollectionUtils.isEmpty(retainedChunkIds) ? List.of() : List.copyOf(retainedChunkIds);
    }

    /**
     * 三参便捷构造器（退化语义）：委托四参规范化构造器并传入空装入集，
     * 既有构造点（如 Stage 4 异常降级路径）零改动即获得「空表=通路 B 回落」语义。
     *
     * @param contextData   上下文文本
     * @param referenceList 引用列表
     * @param kgResult      结构化图谱结果
     */
    public KgContext(String contextData, List<String> referenceList, KgSearchResult kgResult) {
        this(contextData, referenceList, kgResult, List.of());
    }
}
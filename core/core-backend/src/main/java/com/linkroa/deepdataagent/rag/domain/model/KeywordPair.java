package com.linkroa.deepdataagent.rag.domain.model;

import java.util.List;

/**
 * 双层关键词值对象（查询理解 Stage 0 产物）。
 * <p>{@code hl} 为高层关键词（面向关系路检索），{@code ll} 为低层关键词
 * （面向实体路检索）；列表均允许为空，由调用方按空兜底规则处理。</p>
 *
 * @param hl 高层关键词列表（关系路方向）
 * @param ll 低层关键词列表（实体路方向）
 */
public record KeywordPair(
        List<String> hl,
        List<String> ll
) {
}
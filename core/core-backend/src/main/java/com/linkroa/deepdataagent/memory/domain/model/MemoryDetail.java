package com.linkroa.deepdataagent.memory.domain.model;

/**
 * 记忆详情只读模型（记忆条目 + 当前生效内容）。
 * <p>读取单条 memory 时组装：{@code entry} 为条目元数据，{@code content} 为头版本
 * 对应的当前内容；列表场景不返回 content（直接消费 {@link Memory}）。</p>
 *
 * @param entry   记忆条目
 * @param content 当前内容（头版本已脱敏时为 null）
 */
public record MemoryDetail(Memory entry, String content) {

    public MemoryDetail {
        if (entry == null) {
            throw new IllegalArgumentException("记忆条目不能为空");
        }
    }
}

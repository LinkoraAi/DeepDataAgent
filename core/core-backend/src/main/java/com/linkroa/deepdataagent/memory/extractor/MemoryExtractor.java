package com.linkroa.deepdataagent.memory.extractor;

import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;

import java.util.List;

/**
 * 记忆提取器接口。
 *
 * <p>定义从对话上下文中提取并分类长期记忆的标准契约。
 * 支持多种实现策略：LLM 驱动、关键词匹配降级等。</p>
 */
public interface MemoryExtractor {

    /**
     * 从对话上下文中提取并分类记忆。
     *
     * @param context 对话上下文
     * @return 提取的记忆列表，可能为空
     */
    List<ExtractedMemory> extractAndClassify(ConversationContext context);
}

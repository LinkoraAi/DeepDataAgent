package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;

/**
 * 从对话中提取出的候选长期记忆。
 *
 * <p>该对象是写入 Markdown 真相源之前的中间模型，包含层级、子类别、正文、
 * 重要性和来源会话等信息。</p>
 */
public record ExtractedMemory(
        String id,
        String layer,
        String subCategory,
        String title,
        String content,
        double importance,
        Instant createdAt,
        String sourceSessionId
) {
}

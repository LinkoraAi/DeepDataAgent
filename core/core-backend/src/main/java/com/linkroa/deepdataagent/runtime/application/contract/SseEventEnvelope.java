package com.linkroa.deepdataagent.runtime.application.contract;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话事件订阅（SSE / REST 回放）对外输出的统一 {@code Message} 事件信封（发布语言 DTO）。
 * <p>{@code object} 恒为 {@code message}，{@code type} 为事件类型判别字段（领域事件类型小写），
 * {@code content} 为结构化 ContentBlock 数组（文本块或 {@code data} 块）。字段名直接采用
 * snake_case 与线上契约一致，运行时侧不再暴露领域/审计字段。</p>
 */
public record SseEventEnvelope(
        String object,
        String id,
        OffsetDateTime created_at,
        String role,
        String type,
        List<Map<String, Object>> content,
        Map<String, Object> metadata,
        String status,
        long sequence_number
) {
    public SseEventEnvelope {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("事件 ID 不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("事件内容不能为空");
        }
    }
}
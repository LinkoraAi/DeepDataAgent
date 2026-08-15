package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;

/**
 * 聊天事件响应 DTO（SSE {@code data} 的 JSON 结构一致）。
 */
public record ChatEventResponse(
        String eventId,
        String sessionId,
        String roundId,
        String eventType,
        String payload,
        long sequenceNum,
        OffsetDateTime createdAt
) {
}
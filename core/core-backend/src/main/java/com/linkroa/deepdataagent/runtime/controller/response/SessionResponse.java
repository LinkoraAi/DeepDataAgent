package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;

/**
 * 会话响应 DTO。
 */
public record SessionResponse(
        String sessionId,
        String userId,
        String agentId,
        String agentVersion,
        String status,
        String metadata,
        String sandboxId,
        String title,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
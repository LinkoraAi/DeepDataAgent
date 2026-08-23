package com.linkroa.deepdataagent.memory.controller.response;

import java.time.OffsetDateTime;

/**
 * 记忆库响应 DTO
 */
public record MemoryStoreResponse(
        String memoryId,
        String name,
        String type,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
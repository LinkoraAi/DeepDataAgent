package com.linkroa.deepdataagent.memory.controller.response;

import java.time.OffsetDateTime;

/**
 * 记忆库响应 DTO（Store 聚合根，含状态与统计列）。
 *
 * @param status      状态（active/archived）
 * @param entryCount  活跃记忆条目数
 * @param totalSize   活跃记忆内容总字节数
 * @param archivedAt  归档时间（null=未归档）
 */
public record MemoryStoreResponse(
        String storeId,
        String name,
        String description,
        String status,
        int entryCount,
        long totalSize,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

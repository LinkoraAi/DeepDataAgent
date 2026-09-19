package com.linkroa.deepdataagent.memory.controller.response;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 记忆条目响应 DTO（列表项：含 id/path/size/contentSha256/version，MUST 不含 content 全文）。
 */
public record MemoryResponse(
        String memoryId,
        String storeId,
        String path,
        int version,
        long size,
        String contentSha256,
        Map<String, String> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

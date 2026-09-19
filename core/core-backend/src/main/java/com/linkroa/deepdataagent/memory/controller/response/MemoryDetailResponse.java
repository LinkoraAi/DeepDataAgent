package com.linkroa.deepdataagent.memory.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 记忆详情响应 DTO（条目元数据 + 头版本内容；仅创建 / 更新 / 单条查询返回）。
 * <p>头版本已脱敏时 {@code content} 为 null 且序列化省略。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryDetailResponse(
        String memoryId,
        String storeId,
        String path,
        int version,
        long size,
        String contentSha256,
        Map<String, String> metadata,
        String content,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

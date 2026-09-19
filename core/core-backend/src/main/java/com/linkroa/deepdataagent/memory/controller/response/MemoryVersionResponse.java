package com.linkroa.deepdataagent.memory.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * 记忆版本响应 DTO（版本历史 / 单版本查询 / redact 结果）。
 * <p>已脱敏或墓碑版本 {@code content} / {@code contentSha256} 为 null 且序列化省略
 * （redact 后响应 MUST NOT 含 content 字段）；{@code size} 脱敏后保留。</p>
 *
 * @param action 动作类型（created/updated/deleted，deleted 为 tombstone 墓碑）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryVersionResponse(
        String versionId,
        String storeId,
        String entryId,
        String entryPath,
        int version,
        String action,
        String content,
        Long size,
        String contentSha256,
        boolean redacted,
        OffsetDateTime redactedAt,
        OffsetDateTime createdAt
) {
}

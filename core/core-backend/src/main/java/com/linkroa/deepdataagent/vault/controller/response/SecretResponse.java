package com.linkroa.deepdataagent.vault.controller.response;

import java.time.OffsetDateTime;

/**
 * 密钥响应 DTO（明文永不下发，仅返回脱敏标记）
 */
public record SecretResponse(
        String secretId,
        String name,
        /** 脱敏后的密钥值（固定占位，永不返回明文） */
        String maskedValue,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
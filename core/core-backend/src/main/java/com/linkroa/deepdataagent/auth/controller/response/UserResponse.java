package com.linkroa.deepdataagent.auth.controller.response;

import java.time.OffsetDateTime;

/**
 * 用户响应 DTO（不含任何密码明文 / 散列）。
 */
public record UserResponse(
        Long userId,
        String email,
        OffsetDateTime createdAt
) {
}
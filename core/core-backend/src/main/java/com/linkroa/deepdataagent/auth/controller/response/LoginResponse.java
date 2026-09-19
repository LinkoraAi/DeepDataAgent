package com.linkroa.deepdataagent.auth.controller.response;

/**
 * 登录响应 DTO。
 */
public record LoginResponse(
        String token,
        long expiresInSeconds,
        Long userId,
        String email
) {
}
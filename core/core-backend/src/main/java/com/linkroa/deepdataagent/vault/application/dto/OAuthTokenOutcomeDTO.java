package com.linkroa.deepdataagent.vault.application.dto;

/**
 * OAuth 令牌端点换码结果（应用层材料化 DTO，仅进程内流转）。
 *
 * <p>与 {@link OAuthRefreshOutcomeDTO} 同形（换码与刷新共用同一段令牌端点解析逻辑），
 * 但语义独立：本 DTO 承载 {@code grant_type=authorization_code} 的结果，
 * {@code accessToken} 为空即「未换得令牌」，由应用层按分类规则裁决后拒绝落库。</p>
 *
 * @param accessToken      换得的访问令牌（空 = 未换得令牌；明文仅内存持有）
 * @param refreshToken     换得的刷新令牌（可空 = 授权服务器未下发）
 * @param expiresInSeconds 访问令牌有效期秒数（可空 = 未声明到期）
 * @param httpResponse     响应诊断（已脱敏截断；无响应时为 null）
 */
public record OAuthTokenOutcomeDTO(
        String accessToken,
        String refreshToken,
        Integer expiresInSeconds,
        HttpDiagnosticDTO httpResponse
) {
}
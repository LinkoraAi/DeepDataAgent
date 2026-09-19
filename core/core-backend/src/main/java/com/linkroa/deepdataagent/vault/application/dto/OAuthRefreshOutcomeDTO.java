package com.linkroa.deepdataagent.vault.application.dto;

/**
 * 令牌刷新调用的原始结果（仅进程内，由 {@code VaultOAuthRefreshPort} 返回，见 design D4）。
 *
 * <p>本 DTO 只承载<b>事实</b>，不做分类裁决——{@code succeeded} / {@code failed} /
 * {@code connect_error} 的判定在应用层按 D4 规则统一收敛（避免分类口径散落两处）。</p>
 *
 * @param accessToken      换取到的新访问令牌（未换取成功为 null）
 * @param refreshToken     服务商轮换后的刷新令牌（未轮换为 null，调用方沿用原值）
 * @param expiresInSeconds 响应携带的剩余有效期秒数（未携带为 null）
 * @param httpResponse     收到的 HTTP 响应（null = 未收到任何响应：连接错误 / 超时 / 超出信任边界）
 */
public record OAuthRefreshOutcomeDTO(
        String accessToken,
        String refreshToken,
        Integer expiresInSeconds,
        HttpDiagnosticDTO httpResponse
) {
}
package com.linkroa.deepdataagent.vault.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 凭证校验响应 DTO（对齐 design D4 契约形状，不含任何明文）。
 *
 * <p>{@code NON_NULL} 省略策略承载「{@code mcp_probe} 仅探测失败时出现」的语义约定：
 * 成功时该对象无信息量，序列化 MUST 整键省略（而非返回空对象或 null），
 * 否则前端无法以「是否存在」判断探测是否失败。</p>
 *
 * @param credentialId    被校验凭证的业务 ID
 * @param vaultId         凭证所属保管库业务 ID
 * @param type            响应类型标识（固定 {@code vault_credential_validation}）
 * @param status          校验结论（{@code valid} / {@code invalid} / {@code unknown}）
 * @param validatedAt     校验时刻（RFC 3339 UTC）
 * @param hasRefreshToken 该凭证是否持有刷新配置
 * @param refresh         刷新诊断（四态；失败时含已脱敏截断的响应诊断）
 * @param mcpProbe        MCP 握手探测诊断（<b>仅探测失败时出现</b>，含失败的调用名）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultCredentialValidationResponse(
        @JsonProperty("credential_id") String credentialId,
        @JsonProperty("vault_id") String vaultId,
        String type,
        String status,
        @JsonProperty("validated_at") OffsetDateTime validatedAt,
        @JsonProperty("has_refresh_token") boolean hasRefreshToken,
        Refresh refresh,
        @JsonProperty("mcp_probe") McpProbe mcpProbe
) {

    /** 响应类型标识（契约固定值）。 */
    public static final String TYPE = "vault_credential_validation";

    /**
     * 刷新诊断。
     *
     * @param status       刷新结论（{@code no_refresh_token} / {@code succeeded} / {@code failed} / {@code connect_error}）
     * @param httpResponse 令牌端点响应诊断（未收到响应时为 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Refresh(String status, @JsonProperty("http_response") HttpResponse httpResponse) {
    }

    /**
     * MCP 握手探测诊断（仅探测失败时出现）。
     *
     * @param method       失败的 MCP 调用名（如 {@code initialize}）
     * @param httpResponse MCP 服务器响应诊断（未收到响应时为 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpProbe(String method, @JsonProperty("http_response") HttpResponse httpResponse) {
    }

    /**
     * 外部 HTTP 响应诊断（响应体已脱敏且有长度上限）。
     *
     * @param statusCode    HTTP 状态码
     * @param contentType   响应 Content-Type
     * @param body          已脱敏 + 已截断的响应体
     * @param bodyTruncated 响应体是否因超长被截断
     */
    public record HttpResponse(
            @JsonProperty("status_code") int statusCode,
            @JsonProperty("content_type") String contentType,
            String body,
            @JsonProperty("body_truncated") boolean bodyTruncated
    ) {
    }
}
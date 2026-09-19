package com.linkroa.deepdataagent.vault.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 凭证响应 DTO（固定脱敏形状，密文 / 明文一律不下发）。
 *
 * <p>{@code auth} 为嵌套对象，仅含非密的类型与目标信息（{@code type} / {@code mcp_server_url} /
 * {@code expires_at}）以及<b>去除密文后</b>的 {@code refresh} 配置（{@code refresh_token} 与
 * {@code token_endpoint_auth.client_secret} MUST NOT 出现）。{@code display_name} 当前固定为
 * {@code null}（不持久化），保留在形状中以稳定前端取字段路径。</p>
 *
 * @param id          凭证业务 ID（新写入为 {@code vcred_} 前缀；存量 {@code cr_} 原样返回）
 * @param type        资源类型标识（固定 {@code vault_credential}）
 * @param vaultId     所属保管库业务 ID
 * @param auth        脱敏鉴权信息
 * @param displayName 显示名称（当前固定 null）
 * @param metadata    元数据键值对象（无 metadata 时为空对象）
 * @param archivedAt  归档时间（null=未归档）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record VaultCredentialResponse(
        String id,
        String type,
        @JsonProperty("vault_id") String vaultId,
        Auth auth,
        @JsonProperty("display_name") String displayName,
        Map<String, Object> metadata,
        @JsonProperty("archived_at") OffsetDateTime archivedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {

    /** 资源类型标识（契约固定值）。 */
    public static final String TYPE = "vault_credential";

    /**
     * 脱敏鉴权信息（身份字段 + 到期时间 + 去除密文的刷新配置）。
     *
     * @param type          鉴权类型（{@code static_bearer} / {@code mcp_oauth}）
     * @param mcpServerUrl  绑定的 MCP 服务器 URL
     * @param expiresAt     访问令牌到期时间（null=无到期）
     * @param refresh       刷新配置（无刷新配置时省略）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Auth(
            String type,
            @JsonProperty("mcp_server_url") String mcpServerUrl,
            @JsonProperty("expires_at") OffsetDateTime expiresAt,
            Refresh refresh
    ) {
    }

    /**
     * 脱敏刷新配置（{@code refresh_token} 与客户端密钥 MUST NOT 出现）。
     *
     * @param clientId          令牌端点分配的客户端 ID（身份字段）
     * @param tokenEndpoint     令牌端点 URL（身份字段）
     * @param resource          目标资源标识（可空）
     * @param scope             授权范围（可空）
     * @param tokenEndpointAuth 令牌端点鉴权方式（仅类型，密钥不返回）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Refresh(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("token_endpoint") String tokenEndpoint,
            String resource,
            String scope,
            @JsonProperty("token_endpoint_auth") TokenEndpointAuth tokenEndpointAuth
    ) {
    }

    /**
     * 令牌端点鉴权方式（仅返回方式本身，密钥只写不读）。
     *
     * @param type 鉴权方式（{@code none} / {@code client_secret_basic} / {@code client_secret_post}）
     */
    public record TokenEndpointAuth(String type) {
    }
}
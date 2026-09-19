package com.linkroa.deepdataagent.vault.application.dto;

import java.time.OffsetDateTime;

/**
 * 凭证脱敏发布视图（应用层 → 协议层的唯一凭证出口）。
 *
 * <p>响应面 MUST NOT 下发任何密文 / 明文：本视图只承载非密列（类型、目标 URL、到期时间、
 * metadata）与<b>解密信封后仅取非密成分</b>的刷新配置（令牌端点身份字段、scope、鉴权方式），
 * 访问令牌 / 刷新令牌 / 客户端密钥一律在装配时丢弃（见 design D5 与「凭证只写不读」）。</p>
 *
 * <p>凭证列表 / 详情 / 写入响应的字段装配只从本视图取，避免协议层直接接触密文列。</p>
 *
 * @param credentialId 凭证业务 ID
 * @param vaultId      所属保管库业务 ID
 * @param authType     鉴权类型（{@code static_bearer} / {@code mcp_oauth}）
 * @param mcpServerUrl 绑定的 MCP 服务器 URL
 * @param expiresAt    访问令牌到期时间（null=无到期）
 * @param refresh      脱敏刷新配置（null=无刷新配置或密文不可解，不展示）
 * @param metadata     元数据 JSON 文本（可空）
 * @param archivedAt   归档时间（null=未归档）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record VaultCredentialViewDTO(
        String credentialId,
        String vaultId,
        String authType,
        String mcpServerUrl,
        OffsetDateTime expiresAt,
        Refresh refresh,
        String metadata,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * 脱敏刷新配置（身份字段 + 非密参数，<b>不含</b> {@code refresh_token} 与 {@code client_secret}）。
     *
     * @param clientId              令牌端点分配的客户端 ID
     * @param tokenEndpoint         令牌端点 URL
     * @param resource              目标资源标识（可空）
     * @param scope                 授权范围（可空）
     * @param tokenEndpointAuthType 令牌端点鉴权方式（仅类型）
     */
    public record Refresh(
            String clientId,
            String tokenEndpoint,
            String resource,
            String scope,
            String tokenEndpointAuthType
    ) {
    }
}
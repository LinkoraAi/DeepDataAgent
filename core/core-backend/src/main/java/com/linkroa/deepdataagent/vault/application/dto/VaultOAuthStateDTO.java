package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * OAuth 授权 state 载荷（应用层 DTO，Redis 承载，见 design D7）。
 *
 * <p>一次性 state 在 Redis 中只存「回调换码所需的上下文」：owner 绑定、目标保管库与
 * MCP 服务器 URL、客户端身份、PKCE 校验串与回调地址。<b>客户端密钥不以明文进入 Redis</b>——
 * {@link #clientSecretCiphertext()} 承载其密文（Base64），回调消费后再解密到内存。</p>
 *
 * <p>TTL 由 Redis 键过期承担（默认 300s），一次性由「消费即删除」的原子读删保证；
 * 键值一经消费即不可重放。</p>
 *
 * @param ownerId                 发起授权的用户 ID（owner 绑定，回调落库归属此人）
 * @param vaultId                 目标保管库业务 ID
 * @param mcpServerUrl            目标 MCP 服务器 URL（凭证绑定键，回调落库不可改）
 * @param clientId                客户端 ID（显式提供或动态注册所得）
 * @param clientSecretCiphertext  客户端密钥密文（Base64；公开客户端为 null）
 * @param codeVerifier            PKCE 校验串（明文，仅存于 Redis 短期载荷）
 * @param tokenEndpoint           令牌端点 URL（发现所得，换码用）
 * @param tokenEndpointAuthType   令牌端点鉴权方式（已格式化字域字符串）
 * @param redirectUri             回调地址（服务端配置，换码时须与授权请求全等）
 * @param resource                受保护资源标识（可空，RFC 8707）
 * @param scope                   授权请求的 scope（可空；由 metadata 发现所得）
 * @param origin                  发起授权的前端来源（回调页面 postMessage 的目标源）
 */
public record VaultOAuthStateDTO(
        Long ownerId,
        String vaultId,
        String mcpServerUrl,
        String clientId,
        String clientSecretCiphertext,
        String codeVerifier,
        String tokenEndpoint,
        String tokenEndpointAuthType,
        String redirectUri,
        String resource,
        String scope,
        String origin
) {

    /**
     * 紧凑构造器：载荷边界校验（换码必需要素非空，防残缺 state 进入回调路径）。
     */
    public VaultOAuthStateDTO {
        if (ownerId == null) {
            throw new IllegalArgumentException("state 载荷缺少 owner");
        }
        requireText(vaultId, "state 载荷缺少 vault_id");
        requireText(mcpServerUrl, "state 载荷缺少 mcp_server_url");
        requireText(clientId, "state 载荷缺少 client_id");
        requireText(codeVerifier, "state 载荷缺少 PKCE 校验串");
        requireText(tokenEndpoint, "state 载荷缺少 token_endpoint");
        requireText(tokenEndpointAuthType, "state 载荷缺少 token_endpoint_auth 方式");
        requireText(redirectUri, "state 载荷缺少回调地址");
        requireText(origin, "state 载荷缺少来源");
    }

    private static void requireText(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
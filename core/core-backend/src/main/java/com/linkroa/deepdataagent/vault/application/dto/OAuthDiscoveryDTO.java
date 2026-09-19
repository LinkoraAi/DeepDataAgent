package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * MCP OAuth 授权服务器 metadata 发现结果（应用层材料化 DTO，仅进程内流转，见 design D8）。
 *
 * <p>由 {@code VaultOAuthDiscoveryPort} 出网发现后回传：授权端点与令牌端点必取，
 * 注册端点与各类「支持能力」列表用于裁决「授权服务器是否支持必需行为」——
 * {@code code_challenge_methods_supported} MUST 含 {@code S256}（否则 400），
 * {@code token_endpoint_auth_methods_supported} 决定客户端密钥承载方式。</p>
 *
 * @param authorizationEndpoint              授权端点（必填）
 * @param tokenEndpoint                      令牌端点（必填）
 * @param registrationEndpoint               动态客户端注册端点（可空 = 不支持注册）
 * @param codeChallengeMethodsSupported      PKCE 挑战方式（空 = 未声明，按不支持处理）
 * @param tokenEndpointAuthMethodsSupported  令牌端点鉴权方式（空 = 未声明）
 * @param scopesSupported                    授权服务器支持的 scope（空 = 不显式申请 scope）
 * @param resource                           受保护资源标识（可空，RFC 8707 / RFC 9728）
 */
public record OAuthDiscoveryDTO(
        String authorizationEndpoint,
        String tokenEndpoint,
        String registrationEndpoint,
        List<String> codeChallengeMethodsSupported,
        List<String> tokenEndpointAuthMethodsSupported,
        List<String> scopesSupported,
        String resource
) {

    /** PKCE 强制算法（公开契约要求发起授权 MUST 使用 S256）。 */
    public static final String PKCE_METHOD_S256 = "S256";

    /**
     * 紧凑构造器：契约边界校验（两端点必填；能力列表缺失归一为空列表）。
     */
    public OAuthDiscoveryDTO {
        if (StringUtils.isBlank(authorizationEndpoint)) {
            throw new IllegalArgumentException("授权服务器 metadata 缺少 authorization_endpoint");
        }
        if (StringUtils.isBlank(tokenEndpoint)) {
            throw new IllegalArgumentException("授权服务器 metadata 缺少 token_endpoint");
        }
        codeChallengeMethodsSupported = codeChallengeMethodsSupported == null
                ? List.of() : List.copyOf(codeChallengeMethodsSupported);
        tokenEndpointAuthMethodsSupported = tokenEndpointAuthMethodsSupported == null
                ? List.of() : List.copyOf(tokenEndpointAuthMethodsSupported);
        scopesSupported = scopesSupported == null ? List.of() : List.copyOf(scopesSupported);
    }

    /**
     * 授权服务器是否支持 PKCE S256（不支持即无法发起授权，返回 400）。
     */
    public boolean supportsS256() {
        return codeChallengeMethodsSupported.stream()
                .anyMatch(method -> PKCE_METHOD_S256.equalsIgnoreCase(method));
    }

    /**
     * 是否支持某令牌端点鉴权方式。
     */
    public boolean supportsTokenEndpointAuthMethod(String method) {
        return tokenEndpointAuthMethodsSupported.stream()
                .anyMatch(supported -> supported.equalsIgnoreCase(method));
    }

    /**
     * 是否声明动态客户端注册端点。
     */
    public boolean supportsDynamicRegistration() {
        return StringUtils.isNotBlank(registrationEndpoint);
    }

    /**
     * 发现到的 scopes 拼接为空格分隔的 scope 参数（未声明返回 null = 不显式申请）。
     */
    public String scopeParameter() {
        return scopesSupported.isEmpty() ? null : String.join(" ", scopesSupported);
    }
}
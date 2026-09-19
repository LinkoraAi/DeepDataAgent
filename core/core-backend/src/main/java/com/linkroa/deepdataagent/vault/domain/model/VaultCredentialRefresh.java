package com.linkroa.deepdataagent.vault.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * OAuth 刷新配置值对象（{@code auth.refresh}，随密文信封整包加密）。
 *
 * <p>承载 {@code mcp_oauth} 凭证的刷新四要素与可选参数。其中
 * {@link #clientId()} 与 {@link #tokenEndpoint()} 是<b>身份字段</b>：决定「向谁刷新」，
 * 一经创建不可修改（更新补丁出现即拒 400）；其余字段（刷新令牌、鉴权方式、scope、resource）
 * 可经更新补丁原地替换。</p>
 *
 * <p>不变量：刷新令牌必须存在——缺失刷新令牌的「半残刷新配置」会让后续刷新路径必然失败，
 * 违背更新契约「refresh 只能修补已有配置、不能新增」的立意。</p>
 *
 * @param clientId          令牌端点分配的客户端 ID（身份字段，必填）
 * @param refreshToken      刷新令牌（必填，密文成分）
 * @param tokenEndpoint     令牌端点 URL（身份字段，必填）
 * @param tokenEndpointAuth 令牌端点鉴权（必填）
 * @param resource          目标资源标识（可选，RFC 8707）
 * @param scope             刷新请求的授权范围（可选，空格分隔；传 null 清除）
 */
public record VaultCredentialRefresh(
        String clientId,
        String refreshToken,
        String tokenEndpoint,
        VaultCredentialTokenEndpointAuth tokenEndpointAuth,
        String resource,
        String scope
) {

    /**
     * 紧凑构造器：不变量校验（身份字段与刷新令牌必填 + 令牌端点鉴权必填）。
     */
    public VaultCredentialRefresh {
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("刷新配置的 client_id 不能为空");
        }
        if (StringUtils.isBlank(refreshToken)) {
            throw new IllegalArgumentException("刷新配置的 refresh_token 不能为空");
        }
        if (StringUtils.isBlank(tokenEndpoint)) {
            throw new IllegalArgumentException("刷新配置的 token_endpoint 不能为空");
        }
        if (tokenEndpointAuth == null) {
            throw new IllegalArgumentException("刷新配置的 token_endpoint_auth 不能为空");
        }
    }

    /**
     * 替换刷新令牌（其余字段保持）。
     */
    public VaultCredentialRefresh withRefreshToken(String newRefreshToken) {
        return new VaultCredentialRefresh(clientId, newRefreshToken, tokenEndpoint,
                tokenEndpointAuth, resource, scope);
    }

    /**
     * 替换授权范围（{@code null} = 清除 scope）。
     */
    public VaultCredentialRefresh withScope(String newScope) {
        return new VaultCredentialRefresh(clientId, refreshToken, tokenEndpoint,
                tokenEndpointAuth, resource, newScope);
    }

    /**
     * 替换令牌端点鉴权（其余字段保持）。
     */
    public VaultCredentialRefresh withTokenEndpointAuth(VaultCredentialTokenEndpointAuth newAuth) {
        return new VaultCredentialRefresh(clientId, refreshToken, tokenEndpoint,
                newAuth, resource, scope);
    }
}
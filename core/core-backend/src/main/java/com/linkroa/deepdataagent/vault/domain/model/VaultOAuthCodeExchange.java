package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.apache.commons.lang3.StringUtils;

/**
 * OAuth 授权码换取令牌值对象（{@code grant_type=authorization_code} 的输入载荷）。
 *
 * <p>承载回调一次性换取令牌所需的全部要素：授权码、PKCE 校验串、回调地址与客户端身份。
 * 其中 {@link #clientSecret()} 为明文成分，只在本值对象的调用栈内存中存在——不落库、
 * 不进响应与日志（Redis 中承载的 state 只保存其密文，见 {@code VaultOAuthStateDTO}）。</p>
 *
 * @param tokenEndpoint          令牌端点 URL（由授权服务器 metadata 发现，请求不可覆盖）
 * @param clientId               客户端 ID（显式提供或动态注册所得）
 * @param clientSecret           客户端密钥（{@code none} 之外必填；明文仅内存持有）
 * @param tokenEndpointAuthType  令牌端点鉴权方式（决定密钥经 Basic 头 / 表单 / 不携带）
 * @param code                   授权服务器回调携带的授权码
 * @param codeVerifier           PKCE 校验串（明文，与授权请求的 S256 挑战配对）
 * @param redirectUri            回调地址（服务端配置，换码时必须与授权请求全等）
 * @param resource               目标资源标识（可空，RFC 8707）
 */
public record VaultOAuthCodeExchange(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        VaultCredentialTokenEndpointAuthType tokenEndpointAuthType,
        String code,
        String codeVerifier,
        String redirectUri,
        String resource
) {

    /**
     * 紧凑构造器：不变量校验（换码必需要素必填 + 非 {@code none} 方式必须携带客户端密钥）。
     */
    public VaultOAuthCodeExchange {
        if (StringUtils.isBlank(tokenEndpoint)) {
            throw new IllegalArgumentException("令牌端点不能为空");
        }
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("客户端 ID 不能为空");
        }
        if (tokenEndpointAuthType == null) {
            throw new IllegalArgumentException("令牌端点鉴权方式不能为空");
        }
        if (tokenEndpointAuthType != VaultCredentialTokenEndpointAuthType.NONE
                && StringUtils.isBlank(clientSecret)) {
            throw new IllegalArgumentException("鉴权方式 " + tokenEndpointAuthType.getValue() + " 必须携带客户端密钥");
        }
        if (StringUtils.isBlank(code)) {
            throw new IllegalArgumentException("授权码不能为空");
        }
        if (StringUtils.isBlank(codeVerifier)) {
            throw new IllegalArgumentException("PKCE 校验串不能为空");
        }
        if (StringUtils.isBlank(redirectUri)) {
            throw new IllegalArgumentException("回调地址不能为空");
        }
    }
}
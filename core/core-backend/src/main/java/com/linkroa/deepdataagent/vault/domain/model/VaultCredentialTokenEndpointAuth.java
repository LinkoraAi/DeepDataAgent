package com.linkroa.deepdataagent.vault.domain.model;

import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.apache.commons.lang3.StringUtils;

/**
 * 令牌端点鉴权值对象（{@code refresh.token_endpoint_auth}）。
 *
 * <p>承载刷新令牌时向令牌端点出示的客户端身份：鉴权方式为
 * {@link VaultCredentialTokenEndpointAuthType#NONE}（公开客户端）时不要求客户端密钥，
 * 其余方式必须携带。该值对象随刷新配置整包加密落入凭据密文信封，
 * 明文仅内存持有，不落库、不进响应与日志。</p>
 *
 * @param type         鉴权方式（必填）
 * @param clientSecret 客户端密钥（{@code none} 以外必填；密文成分）
 */
public record VaultCredentialTokenEndpointAuth(
        VaultCredentialTokenEndpointAuthType type,
        String clientSecret
) {

    /**
     * 紧凑构造器：不变量校验（方式必填 + 非 none 方式必须携带客户端密钥）。
     */
    public VaultCredentialTokenEndpointAuth {
        if (type == null) {
            throw new IllegalArgumentException("令牌端点鉴权方式不能为空");
        }
        if (type != VaultCredentialTokenEndpointAuthType.NONE && StringUtils.isBlank(clientSecret)) {
            throw new IllegalArgumentException("鉴权方式 " + type.getValue() + " 必须携带客户端密钥");
        }
    }

    /**
     * 是否为携带客户端密钥的鉴权方式。
     */
    public boolean secretRequired() {
        return type != VaultCredentialTokenEndpointAuthType.NONE;
    }
}
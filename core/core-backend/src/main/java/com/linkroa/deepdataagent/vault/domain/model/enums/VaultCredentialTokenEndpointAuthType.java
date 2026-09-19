package com.linkroa.deepdataagent.vault.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * OAuth 刷新令牌端点鉴权方式（refresh.token_endpoint_auth.type）。
 *
 * <p>对齐 OAuth 2.1 令牌端点客户端鉴权语义：</p>
 * <ul>
 *   <li>{@link #NONE}：公开客户端，不携带客户端密钥（{@code client_secret} 非必填）；</li>
 *   <li>{@link #CLIENT_SECRET_BASIC}：以 HTTP Basic 携带客户端密钥；</li>
 *   <li>{@link #CLIENT_SECRET_POST}：以表单参数携带客户端密钥。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth
 */
public enum VaultCredentialTokenEndpointAuthType {

    /** 公开客户端（无客户端密钥） */
    NONE("none"),
    /** HTTP Basic 携带客户端密钥 */
    CLIENT_SECRET_BASIC("client_secret_basic"),
    /** 表单参数携带客户端密钥 */
    CLIENT_SECRET_POST("client_secret_post");

    /** 对外暴露的小写源码取值 */
    private final String value;

    VaultCredentialTokenEndpointAuthType(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code client_secret_basic}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析鉴权方式字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的鉴权方式
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static VaultCredentialTokenEndpointAuthType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("令牌端点鉴权方式不能为空");
        }
        for (VaultCredentialTokenEndpointAuthType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知令牌端点鉴权方式: " + value);
    }
}
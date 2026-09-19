package com.linkroa.deepdataagent.vault.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 凭证鉴权类型（VaultCredential.auth_type）。
 *
 * <p>对齐真实 Managed Agents 平台 Vault 凭证的鉴权语义：</p>
 * <ul>
 *   <li>{@link #STATIC_BEARER}：静态 Bearer Token，运行时按 {@code mcp_server_url}
 * 匹配 MCP 连接并注入 {@code Authorization: Bearer <token>}；</li>
 *   <li>{@link #MCP_OAUTH}：MCP OAuth 凭证（本期仅持久化类型，OAuth 流转未落地）。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.vault.domain.model.VaultCredential
 */
public enum VaultCredentialAuthType {

    /** 静态 Bearer Token 鉴权 */
    STATIC_BEARER("static_bearer"),
    /** MCP OAuth 鉴权（本期仅持久化） */
    MCP_OAUTH("mcp_oauth");

    /** 对外暴露的小写源码取值（对齐规范枚举命名小写值域） */
    private final String value;

    VaultCredentialAuthType(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code static_bearer} / {@code mcp_oauth}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析鉴权类型字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的鉴权类型
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static VaultCredentialAuthType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("凭证鉴权类型不能为空");
        }
        for (VaultCredentialAuthType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知凭证鉴权类型: " + value);
    }
}

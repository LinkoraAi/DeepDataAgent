package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 发起 MCP OAuth 授权的返回契约（应用层 DTO，仅进程内流转）。
 *
 * <p>只承载「去哪里授权」所需的三个值：授权地址、一次性不透明 state 与回调地址来源；
 * <b>不含任何凭证材料</b>——客户端密钥与 PKCE 校验串都只存在于服务端（Redis state 载荷）。</p>
 *
 * @param authorizationUrl 授权服务器授权地址（含 S256 挑战与 state）
 * @param state            一次性不透明 state（回调关联用，短期有效）
 * @param callbackOrigin   服务端配置的回调地址来源（scheme://authority，供前端校验同名源）
 */
public record OAuthStartResultDTO(String authorizationUrl, String state, String callbackOrigin) {

    /**
     * 紧凑构造器：契约边界校验（三项必填）。
     */
    public OAuthStartResultDTO {
        if (StringUtils.isBlank(authorizationUrl)) {
            throw new IllegalArgumentException("授权地址不能为空");
        }
        if (StringUtils.isBlank(state)) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (StringUtils.isBlank(callbackOrigin)) {
            throw new IllegalArgumentException("回调地址来源不能为空");
        }
    }
}
package com.linkroa.deepdataagent.vault.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 发起 MCP OAuth 授权响应 DTO（契约形状，不含任何凭证材料）。
 *
 * <p>三个字段即「去哪里授权」的全部所需：授权地址、一次性不透明 state（<b>仅用于关联 popup
 * 回调</b>）与服务端配置的回调地址来源（前端据此校验回调消息的发送方来源）。</p>
 *
 * @param authorizationUrl 授权服务器授权地址（在浏览器中打开）
 * @param state            一次性不透明 state（短期有效）
 * @param callbackOrigin   回调地址来源（{@code scheme://authority}）
 */
public record OAuthStartResponse(
        @JsonProperty("authorization_url") String authorizationUrl,
        String state,
        @JsonProperty("callback_origin") String callbackOrigin
) {
}
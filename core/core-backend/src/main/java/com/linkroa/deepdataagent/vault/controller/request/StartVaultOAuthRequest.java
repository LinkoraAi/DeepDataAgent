package com.linkroa.deepdataagent.vault.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发起 MCP OAuth 授权请求（{@code POST /vaults/oauth/start}）。
 *
 * <p>以 <b>DELEGATING</b> 方式承载原始 JSON 对象体：契约要求
 * {@code protocol} / {@code scope} / {@code redirect_uri} 一律<b>出现即拒</b>（端点与 scopes
 * 由服务端 metadata 发现、回调地址取服务端配置），普通 record 反序列化会把这些未知键静默丢弃，
 * 使「MUST NOT 作为请求字段」被架空，故整体接收 {@code Map} 并由
 * {@link com.linkroa.deepdataagent.vault.application.convert.VaultOAuthCommandConvert#toStartCommand}
 * 做字段白名单裁决。</p>
 *
 * <p>请求体形状：{@code {vault_id, mcp_server_url, client_id?, client_secret?}}。</p>
 *
 * @param fields 原始请求体键值对（空白体 = 空 Map）
 */
public record StartVaultOAuthRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public StartVaultOAuthRequest {
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
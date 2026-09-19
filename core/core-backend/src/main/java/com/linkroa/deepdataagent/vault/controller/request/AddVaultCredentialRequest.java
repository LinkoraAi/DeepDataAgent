package com.linkroa.deepdataagent.vault.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 添加凭证请求（对齐契约的嵌套 {@code auth} 形状）。
 *
 * <p>请求体形如 {@code {"auth": {"type": "static_bearer", "mcp_server_url": "...", "token": "..."}}}；
 * {@code mcp_oauth} 类型以 {@code auth.access_token} 承载访问令牌。整体接收字段映射的原因同
 * {@link CreateVaultRequest}（未知键必须可被察觉并 400）。密钥明文仅在请求体内传递，
 * 落库即加密，绝不回显。</p>
 */
public record AddVaultCredentialRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public AddVaultCredentialRequest {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
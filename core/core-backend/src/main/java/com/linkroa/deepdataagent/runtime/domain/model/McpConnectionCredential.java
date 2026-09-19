package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 连接鉴权材料（领域值对象）：{@code McpCredentialResolver} 按 Target 匹配后的单连接产出。
 * <p>承载该连接装配时需要的 HTTP 请求头（如 {@code Authorization: Bearer <token>}），
 * 明文仅在 JVM 内存持有、止步于 Harness MCP 客户端装配，<b>永不进沙箱数据面 / 日志 / 响应</b>。
 * {@link #toString()} 对全部请求头值强制脱敏，防止明文凭据随日志 / 异常链泄露。</p>
 *
 * @param connectionName MCP 连接名称（非空，同时作为 MCP 配置 Map 的键）
 * @param url            MCP 端点 URL（非空）
 * @param headers        鉴权请求头（可空归一为空 Map；无匹配凭证时为空 = 该连接不注入鉴权）
 */
public record McpConnectionCredential(
        String connectionName,
        String url,
        Map<String, String> headers
) {

    public McpConnectionCredential {
        if (StringUtils.isBlank(connectionName)) {
            throw new IllegalArgumentException("MCP 连接名称不能为空");
        }
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("MCP 连接 URL 不能为空");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * 脱敏 toString：仅暴露请求头键名，任何值一律以掩码呈现。
     */
    @Override
    public String toString() {
        String headerDesc = headers.keySet().stream()
                .map(key -> key + "=****")
                .collect(Collectors.joining(", ", "[", "]"));
        return "McpConnectionCredential[connectionName=" + connectionName
                + ", url=" + url
                + ", headers=" + headerDesc + "]";
    }
}

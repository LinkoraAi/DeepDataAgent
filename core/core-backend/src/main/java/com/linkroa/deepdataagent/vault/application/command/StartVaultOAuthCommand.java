package com.linkroa.deepdataagent.vault.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 发起 MCP OAuth 授权命令（写操作参数对象）。
 *
 * <p>只承载契约允许的四个请求字段：{@code vault_id}、{@code mcp_server_url} 与可选
 * {@code client_id} / {@code client_secret}。<b>OAuth 传输层字段（{@code protocol} /
 * {@code scope} / {@code redirect_uri}）不在命令内</b>——端点与 scopes 由服务端 metadata
 * 发现、回调地址取服务端配置，故请求携带这些字段一律在协议层被拒。</p>
 *
 * @param vaultId      目标保管库业务 ID（必填）
 * @param mcpServerUrl 目标 MCP 服务器 URL（必填，用于 discovery 与凭证匹配）
 * @param clientId     客户端 ID（可空 = 走动态客户端注册）
 * @param clientSecret 客户端密钥（可空；明文仅内存持有，落 Redis 前先加密）
 * @param origin       发起授权的前端来源（可空 = 回落服务端回调地址来源）
 */
public record StartVaultOAuthCommand(
        String vaultId,
        String mcpServerUrl,
        String clientId,
        String clientSecret,
        String origin
) {

    /** MCP 服务器 URL 长度上限（与凭证领域不变量及 V1 列宽一致）。 */
    private static final int MAX_MCP_SERVER_URL_LENGTH = 2048;

    /**
     * 紧凑构造器：不变量校验（必填项 + URL 长度边界）。
     */
    public StartVaultOAuthCommand {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("vault_id 不能为空");
        }
        if (StringUtils.isBlank(mcpServerUrl)) {
            throw new IllegalArgumentException("mcp_server_url 不能为空");
        }
        if (mcpServerUrl.length() > MAX_MCP_SERVER_URL_LENGTH) {
            throw new IllegalArgumentException("mcp_server_url 长度不能超过" + MAX_MCP_SERVER_URL_LENGTH + "个字符");
        }
    }

    /**
     * 是否显式提供了客户端身份（提供 client_id 即不做动态注册）。
     */
    public boolean hasClientId() {
        return StringUtils.isNotBlank(clientId);
    }

    /**
     * 是否携带客户端密钥（决定令牌端点鉴权方式的候选集合）。
     */
    public boolean hasClientSecret() {
        return StringUtils.isNotBlank(clientSecret);
    }
}
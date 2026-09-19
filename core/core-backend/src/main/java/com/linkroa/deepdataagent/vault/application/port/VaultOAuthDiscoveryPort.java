package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.OAuthClientRegistrationDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthDiscoveryDTO;

import java.util.Optional;

/**
 * MCP OAuth 授权服务器 metadata 发现与动态客户端注册出站端口（见 design D8）。
 *
 * <p>出网统一经共享出网执行器（单次解析 + 信任边界复检 + 越界零请求），
 * 故 discovery 与注册端点同样受出网信任边界约束；任何失败（越界 / 不可达 / 报文非法）
 * 一律以空结果承载，由应用层按 400 语义拒绝，不抛异常。</p>
 */
public interface VaultOAuthDiscoveryPort {

    /**
     * 发现某 MCP 服务器对应的授权服务器 metadata。
     *
     * @param mcpServerUrl MCP 服务器 URL（受保护资源，先取其 metadata 定位授权服务器）
     * @return 发现结果（缺少授权 / 令牌端点或出网失败时为空）
     */
    Optional<OAuthDiscoveryDTO> discover(String mcpServerUrl);

    /**
     * 动态客户端注册（授权服务器声明注册端点且未提供 {@code client_id} 时执行）。
     *
     * @param registrationEndpoint 注册端点（metadata 发现所得）
     * @param redirectUri          回调地址（服务端配置）
     * @param clientName           客户端展示名
     * @param tokenEndpointAuthMethod 期望的令牌端点鉴权方式
     * @param scope                申请的 scope（可空）
     * @return 注册结果（未声明注册端点 / 出网失败 / 未返回 client_id 时为空）
     */
    Optional<OAuthClientRegistrationDTO> registerClient(String registrationEndpoint, String redirectUri,
                                                        String clientName, String tokenEndpointAuthMethod,
                                                        String scope);
}
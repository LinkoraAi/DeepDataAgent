package com.linkroa.deepdataagent.vault.application.dto;

/**
 * OAuth 动态客户端注册结果（应用层材料化 DTO，仅进程内流转）。
 *
 * <p>授权服务器未提供 {@code client_id} 且声明注册端点时，由
 * {@code VaultOAuthDiscoveryPort#registerClient} 出网注册所得；注册产出的
 * {@code clientSecret} 为明文，仅内存持有，随后以密文形态存入 Redis state 载荷。</p>
 *
 * @param clientId     注册所得客户端 ID（必填）
 * @param clientSecret 注册所得客户端密钥（公开客户端可为空）
 */
public record OAuthClientRegistrationDTO(String clientId, String clientSecret) {
}
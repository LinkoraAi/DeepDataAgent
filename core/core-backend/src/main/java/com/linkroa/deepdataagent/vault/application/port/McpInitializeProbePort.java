package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.McpProbeOutcomeDTO;

/**
 * MCP 探测出站端口（进程内依赖倒置，见 design D4 / D16）。
 *
 * <p>由 vault BC 自建的最小 MCP 探测：向 MCP 服务器发起一次 JSON-RPC {@code initialize}
 * 调用（携带 {@code Authorization: Bearer <access token>}），以「协议层是否被接受」作为
 * 凭证有效性的实时判据——比单纯的可达性探测更贴近真实使用（鉴权失败在握手阶段即暴露）。</p>
 *
 * <p>本端口只回传事实（发起了哪个调用、是否收到响应、响应诊断），
 * {@code valid} / {@code invalid} / {@code unknown} 的分类由应用层按 D4 规则收敛。
 * 出网经共享信任边界执行器（{@code TrustedEgressClient}），越界目标不发出任何网络请求。</p>
 */
public interface McpInitializeProbePort {

    /**
     * 探测 MCP 服务器是否接受该访问令牌。
     *
     * @param mcpServerUrl MCP 服务器 URL（凭证绑定的目标）
     * @param accessToken  当前可信访问令牌（明文，仅请求头携带，不回传）
     * @return 探测结果（绝不抛异常，任何失败以「未收到响应」承载）
     */
    McpProbeOutcomeDTO probe(String mcpServerUrl, String accessToken);
}
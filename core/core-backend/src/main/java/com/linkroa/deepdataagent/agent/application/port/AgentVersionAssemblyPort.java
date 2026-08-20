package com.linkroa.deepdataagent.agent.application.port;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;

/**
 * Agent「版本 + 模型」解析出站端口（应用契约，开放主机服务边界）。
 * <p>依赖倒置：runtime BC 依赖本端口与其契约 DTO（发布语言），实现由 agent 基础设施
 * {@code DefaultAgentVersionAssemblyPort} 提供。输入 agentId + 发布号（十进制字符串）→
 * 输出运行时装配契约。校验层次：发布号非十进制 / Agent 不存在 / 版本不存在 /
 * Agent 已归档 → 404（无任何全局回退，会话必须绑定真实 Agent 版本台账）；profile 缺失 → 404。</p>
 */
public interface AgentVersionAssemblyPort {

    /**
     * 解析 Agent 版本 + 模型装配契约信息（发布号非十进制 → 404，已归档 → 404）。
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 发布号十进制字符串（如 "1"）
     * @return 装配契约（含解密后的模型凭证）
     */
    ResolvedAgentAssemblyDTO resolve(String agentId, String versionNumber);

    /**
     * 轻量校验 Agent 版本装配链路（不执行凭证解密）：
     * 发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 → 404。
     * <p>供会话创建前置校验链使用，避免仅为校验而解密明文凭证。</p>
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 发布号十进制字符串（如 "1"）
     */
    void assertResolvable(String agentId, String versionNumber);

    /**
     * 解析 Agent 当前最新发布号（十进制字符串，如 "1"）。
     * <p>会话创建仅绑定 {@code agent} 时用于锁定最新版本快照（对齐 Managed Agents，
     * 创建时不传版本号）；Agent 不存在 / 已归档 / 尚未发布版本 → 404。</p>
     *
     * @param agentId Agent 业务 ID
     * @return 最新发布号（十进制字符串）
     */
    String latestVersionNumber(String agentId);
}
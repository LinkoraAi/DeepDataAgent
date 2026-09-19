package com.linkroa.deepdataagent.agent.application.port;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedModelCredentialDTO;

/**
 * Agent「版本 + 模型」解析出站端口（应用契约，开放主机服务边界）。
 * <p>依赖倒置：runtime BC 依赖本端口与其契约 DTO（发布语言），实现由 agent 基础设施
 * {@code DefaultAgentVersionAssemblyPort} 提供。输入 agentId + 发布号（十进制字符串）→
 * 输出运行时装配契约。校验层次：发布号非十进制 / Agent 不存在 / 版本不存在 /
 * Agent 已归档 → 404（无任何全局回退，会话必须绑定真实 Agent 版本台账）；profile 缺失 → 404。</p>
 * <p>owner 隔离：所有解析入口均须显式携带归属用户 {@code ownerId}，agent / profile /
 * 运行环境任一归属不匹配 → 404（不泄露存在性）。异步 / 调度链路无 ThreadLocal 上下文，
 * 故一律显式传参，不得回退 {@code AuthContext}。</p>
 */
public interface AgentVersionAssemblyPort {

    /**
     * 解析 Agent 版本 + 模型装配契约信息（发布号非十进制 → 404，已归档 → 404；
     * agent / profile / 环境归属不匹配 → 404）。
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 发布号十进制字符串（如 "1"）
     * @param ownerId       归属用户 ID（不匹配 → 404，不泄露存在性）
     * @return 装配契约（含解密后的模型凭证）
     */
    ResolvedAgentAssemblyDTO resolve(String agentId, String versionNumber, Long ownerId);

    /**
     * 窄口径解析模型凭据段（凭证材料化专用，供装配缓存命中路径每轮实时注入）。
     * <p>只解密模型 {@code credential} + 取 {@code apiEndpointUrl}，并<b>重跑归属校验</b>
     * （发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 / 归属不匹配 → 404，
     * 语义与 {@link #resolve} 一致，使「TTL 内归属已失效」不被缓存掩盖）。
     * <b>MUST NOT</b> 加载技能正文、解析工具策略或读工作区文件（那是 {@link #resolve} 的全量职责）。</p>
     * <p>方法<b>恒返回非空实例</b>（不返回 {@code null}）；无鉴权模型 {@code credential} 可空。</p>
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 发布号十进制字符串（如 "1"）
     * @param ownerId       归属用户 ID（不匹配 → 404，不泄露存在性）
     * @return 模型凭据契约（含解密后的凭证与 API 端点，明文仅内存持有）
     */
    ResolvedModelCredentialDTO resolveModelCredential(String agentId, String versionNumber, Long ownerId);

    /**
     * 轻量校验 Agent 版本装配链路（不执行凭证解密）：
     * 发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 → 404。
     * <p>供会话创建前置校验链使用，避免仅为校验而解密明文凭证。</p>
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 发布号十进制字符串（如 "1"）
     * @param ownerId       归属用户 ID（不匹配 → 404，不泄露存在性）
     */
    void assertResolvable(String agentId, String versionNumber, Long ownerId);

    /**
     * 解析 Agent 当前最新发布号（十进制字符串，如 "1"）。
     * <p>会话创建仅绑定 {@code agent} 时用于锁定最新版本快照（对齐 Managed Agents，
     * 创建时不传版本号）；Agent 不存在 / 已归档 / 尚未发布版本 → 404。</p>
     *
     * @param agentId Agent 业务 ID
     * @param ownerId 归属用户 ID（不匹配 → 404，不泄露存在性）
     * @return 最新发布号（十进制字符串）
     */
    String latestVersionNumber(String agentId, Long ownerId);

    /**
     * 解析 Agent 当前激活版本号（十进制字符串，如 "1"）。
     * <p>会话创建省略版本号时用于物化激活版本（{@code active_version}，默认随发布同步、
     * 可回滚）；Agent 不存在 / 已归档 / 无激活版本 → 404。</p>
     *
     * @param agentId Agent 业务 ID
     * @param ownerId 归属用户 ID（不匹配 → 404，不泄露存在性）
     * @return 激活版本号（十进制字符串）
     */
    String activeVersionNumber(String agentId, Long ownerId);
}
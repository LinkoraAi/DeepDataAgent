package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;

import java.util.List;

/**
 * 凭证运行时解密注入出站端口（应用契约，开放主机服务边界）。
 * <p>依赖倒置：agent 装配 / runtime 等消费方依赖本端口与其契约 DTO（发布语言），
 * 实现由 vault 基础设施 {@code DefaultVaultCredentialResolutionPort} 提供。
 * 输入保管库集合（vaultIds，取自 Agent 会话挂载配置）→ 输出已解密的全部未归档凭证
 * （含凭证明文，仅内存持有、不落库、不进响应）。非 owner / 已归档保管库归属过滤
 * 与已归档凭证排除均在实现侧完成，杜绝跨用户凭证明文泄露。</p>
 */
public interface VaultCredentialResolutionPort {

    /**
     * 按保管库集合解密注入全部凭证（owner 以当前认证上下文收敛）。
     *
     * @param vaultIds 持有保管库归属的保管库业务 ID集合（空 / null 返回空列表）
     * @return 解密后的凭证契约列表（含明文，仅内存持有）
     */
    List<ResolvedVaultCredentialDTO> resolveVaultCredentials(List<String> vaultIds);

    /**
     * 按保管库集合解密注入全部凭证（显式 owner 版本，异步 / 调度链路专用）。
     * <p>装配等跨线程链路无 ThreadLocal 认证上下文，MUST 显式传入会话归属用户，
     * 不得回退当前认证用户；owner 为 null 一律返回空列表（不泄露任何明文）。</p>
     *
     * @param ownerId  保管库归属用户 ID（越权 / 已归档保管库在实现侧排除）
     * @param vaultIds 保管库业务 ID 集合（空 / null 返回空列表）
     * @return 解密后的凭证契约列表（含明文，仅内存持有）
     */
    List<ResolvedVaultCredentialDTO> resolveVaultCredentials(Long ownerId, List<String> vaultIds);
}
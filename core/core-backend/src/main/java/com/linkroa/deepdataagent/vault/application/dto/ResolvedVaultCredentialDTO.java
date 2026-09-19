package com.linkroa.deepdataagent.vault.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 运行时解密注入凭证契约（应用层材料化 DTO，仅进程内流转，永不发布）。
 * <p>由 vault BC 在应用边界出版，作为 {@code VaultCredentialResolutionPort} 的返回类型，
 * 供 agent / runtime 等消费方经 ACL 获取「凭证元数据 + 解密明文」的数据面载荷。
 * 明文 {@code token} 仅运行时内存持有：不落库、不进响应；本 DTO 强制脱敏
 * {@code toString}，避免敏感明文随日志 / 异常链输出。</p>
 *
 * @param vaultId      所属保管库业务 ID
 * @param credentialId 凭证业务 ID
 * @param authType     凭证鉴权类型（static_bearer / mcp_oauth，已格式化小写字域字符串）
 * @param mcpServerUrl 绑定的 MCP 服务器 URL（运行时按此匹配注入鉴权）
 * @param token        解密后的凭证明文（仅内存持有）
 */
public record ResolvedVaultCredentialDTO(
        String vaultId,
        String credentialId,
        String authType,
        String mcpServerUrl,
        String token
) {

    /**
     * 紧凑构造器：契约边界校验（标识与元数据必填；明文可空但仅内存承载）。
     */
    public ResolvedVaultCredentialDTO {
        if (StringUtils.isBlank(vaultId)) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (StringUtils.isBlank(credentialId)) {
            throw new IllegalArgumentException("凭证ID不能为空");
        }
        if (StringUtils.isBlank(authType)) {
            throw new IllegalArgumentException("凭证鉴权类型不能为空");
        }
        if (StringUtils.isBlank(mcpServerUrl)) {
            throw new IllegalArgumentException("凭证绑定的 MCP 服务器URL不能为空");
        }
    }

    /**
     * 脱敏 toString：凭证明文不随日志 / 异常链输出。
     */
    @Override
    public String toString() {
        return "ResolvedVaultCredentialDTO[vaultId=" + vaultId
                + ", credentialId=" + credentialId
                + ", authType=" + authType
                + ", mcpServerUrl=" + mcpServerUrl
                + ", token=****]";
    }
}

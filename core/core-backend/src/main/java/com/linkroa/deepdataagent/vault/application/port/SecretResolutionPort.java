package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.contract.SecretReferenceDTO;

/**
 * 凭证密钥解析出站端口（应用契约，开放主机服务边界）。
 * <p>依赖倒置：model-profile / runtime 等消费方依赖本端口与其契约 DTO（发布语言），
 * 实现由 vault 基础设施 {@code DefaultSecretResolutionPort} 提供。输入 secretId →
 * 输出已解密的密钥明文（仅内存持有）。密钥不存在 / 已逻辑删除 → 404。</p>
 */
public interface SecretResolutionPort {

    /**
     * 解析密钥明文。
     *
     * @param secretId 密钥业务 ID
     * @return 密钥解析契约（含解密后明文，仅内存持有）
     */
    SecretReferenceDTO resolve(String secretId);
}
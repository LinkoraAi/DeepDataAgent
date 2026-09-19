package com.linkroa.deepdataagent.vault.infrastructure.assembly;

import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialResolutionPort;
import com.linkroa.deepdataagent.vault.application.service.VaultCredentialRefreshService;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.util.VaultCredentialEncryptionUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 凭证运行时解密注入端口实现（{@link VaultCredentialResolutionPort}）。
 * <p>按 Agent 装配传入的保管库集合批量解密注入：先以当前认证用户 owner 隔离批量加载
 * 未归档保管库（越权 / 已归档 / 不存在一律排除），再批量加载其下凭证并逐条 AES-GCM
 * 解密；单条凭证解密失败仅告警跳过（可选挂载不阻断装配）。明文仅在本实现内存持有，
 * 经脱敏 {@code ResolvedVaultCredentialDTO} 出站，不落库、不进响应与日志。</p>
 *
 * <p><b>临期刷新在这个接缝上发生</b>（design D6 / D7）：本端口正是「MCP discovery 或执行前」
 * 的物理位置，故 {@code mcp_oauth} 凭证在此按需刷新——未临期 / 无 refresh token 一律不刷新，
 * 刷新成功即原子持久化轮换结果，注入的恒为<b>当前可用的访问令牌</b>（不是加密信封原文）。</p>
 */
@Component
public class DefaultVaultCredentialResolutionPort implements VaultCredentialResolutionPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultVaultCredentialResolutionPort.class);

    @Resource
    private VaultRepository vaultRepository;

    @Resource
    private VaultCredentialRepository credentialRepository;

    @Resource
    private VaultCredentialEncryptionUtil encryptionUtil;

    @Resource
    private VaultCredentialRefreshService refreshService;

    @Override
    public List<ResolvedVaultCredentialDTO> resolveVaultCredentials(List<String> vaultIds) {
        if (vaultIds == null || vaultIds.isEmpty()) {
            return List.of();
        }
        // 同步请求链路：以当前认证用户收敛 owner 隔离
        return resolveVaultCredentials(AuthContext.requireUserId(), vaultIds);
    }

    @Override
    public List<ResolvedVaultCredentialDTO> resolveVaultCredentials(Long ownerId, List<String> vaultIds) {
        if (ownerId == null || vaultIds == null || vaultIds.isEmpty()) {
            return List.of();
        }
        // owner 隔离 + 排除已归档：越权 / 失效的保管库引用不会注入数据面，防跨用户明文泄露
        List<Vault> ownedVaults = vaultRepository.findByIds(ownerId, vaultIds);
        if (ownedVaults.isEmpty()) {
            return List.of();
        }
        Set<String> ownedVaultIdSet = ownedVaults.stream().map(Vault::vaultId).collect(Collectors.toSet());

        List<ResolvedVaultCredentialDTO> resolved = new ArrayList<>();
        for (VaultCredential credential : credentialRepository.findByVaultIds(new ArrayList<>(ownedVaultIdSet))) {
            if (credential.archived()) {
                // 归档凭证不再用于新 Session 挂载注入
                continue;
            }
            try {
                resolved.add(new ResolvedVaultCredentialDTO(
                        credential.vaultId(),
                        credential.credentialId(),
                        credential.authType().getValue(),
                        credential.mcpServerUrl(),
                        resolveToken(credential, encryptionUtil.decrypt(credential.ciphertext()))
                ));
            } catch (RuntimeException e) {
                // 单条凭证解密 / 解析 / 刷新失败不阻断装配：记录告警并跳过该凭证注入
                log.warn("凭证解密失败，跳过注入: vaultId={}, credentialId={}, reason={}",
                        credential.vaultId(), credential.credentialId(), e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * 解析注入令牌：{@code mcp_oauth} 凭证先解析加密信封，并在临期时按需刷新
     * （返回恒为当前可用的访问令牌）；{@code static_bearer} 凭证的明文即令牌本体。
     */
    private String resolveToken(VaultCredential credential, String plaintext) {
        if (credential.authType() != VaultCredentialAuthType.MCP_OAUTH) {
            return plaintext;
        }
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(plaintext);
        return refreshService.refreshIfNeeded(credential, material).accessToken();
    }
}
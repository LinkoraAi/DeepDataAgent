package com.linkroa.deepdataagent.vault.infrastructure.assembly;

import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 保管库引用查询服务契约实现（{@link VaultReferenceApi}）：按业务 ID 批量解析保管库引用为发布语言 DTO。
 * <p>复用仓储 owner 隔离 + 排除已归档的批量查询（{@code findByIds}），
 * 仅映射 ID 与显示名称，不触碰凭证聚合。</p>
 */
@Component
public class DefaultVaultReferenceApi implements VaultReferenceApi {

    @Resource
    private VaultRepository vaultRepository;

    @Override
    public List<VaultReferenceDTO> resolveByIds(Long ownerId, List<String> vaultIds) {
        if (ownerId == null || vaultIds == null || vaultIds.isEmpty()) {
            return List.of();
        }
        return vaultRepository.findByIds(ownerId, vaultIds).stream()
                .map(DefaultVaultReferenceApi::toReference)
                .toList();
    }

    private static VaultReferenceDTO toReference(Vault vault) {
        return new VaultReferenceDTO(vault.vaultId(), vault.displayName());
    }
}

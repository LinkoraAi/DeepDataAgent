package com.linkroa.deepdataagent.vault.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialListFilter;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.infrastructure.convert.VaultCredentialPersistenceConvert;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultCredentialEntity;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper.VaultCredentialMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 凭证仓储实现（MyBatis-Plus）。
 */
@Repository
public class JdbcVaultCredentialRepository implements VaultCredentialRepository {

    private final VaultCredentialMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 凭证表 Mapper
     */
    public JdbcVaultCredentialRepository(VaultCredentialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public VaultCredential save(VaultCredential credential) {
        VaultCredentialEntity entity = VaultCredentialPersistenceConvert.INSTANCE.toEntity(credential);
        entity.setId(null);
        mapper.insert(entity);
        return findByVaultIdAndCredentialId(credential.vaultId(), credential.credentialId()).orElse(credential);
    }

    @Override
    public VaultCredential update(VaultCredential credential) {
        VaultCredentialEntity entity = VaultCredentialPersistenceConvert.INSTANCE.toEntity(credential);
        mapper.updateById(entity);
        return findByVaultIdAndCredentialId(credential.vaultId(), credential.credentialId()).orElse(credential);
    }

    @Override
    public boolean updateIfCipherUnchanged(VaultCredential credential, byte[] expectedCiphertext) {
        VaultCredentialEntity entity = VaultCredentialPersistenceConvert.INSTANCE.toEntity(credential);
        return mapper.updateCipherIfUnchanged(credential.vaultId(), credential.credentialId(),
                entity.getCiphertext(), entity.getExpiresAt(), expectedCiphertext) > 0;
    }

    @Override
    public Optional<VaultCredential> findByVaultIdAndCredentialId(String vaultId, String credentialId) {
        return Optional.ofNullable(VaultCredentialPersistenceConvert.INSTANCE.toDomain(
                mapper.selectByVaultIdAndCredentialId(vaultId, credentialId)));
    }

    @Override
    public List<VaultCredential> findByVaultIds(List<String> vaultIds) {
        return mapper.selectByVaultIds(vaultIds).stream()
                .map(VaultCredentialPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<VaultCredential> listByVaultId(String vaultId) {
        return mapper.selectByVaultId(vaultId).stream()
                .map(VaultCredentialPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<VaultCredential> findByCursor(String vaultId, VaultCredentialListFilter filter, int limit) {
        return mapper.selectByCursor(vaultId, filter, limit).stream()
                .map(VaultCredentialPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countActiveByVaultId(String vaultId) {
        return mapper.countActiveByVaultId(vaultId);
    }

    @Override
    public boolean existsActiveByVaultIdAndUrl(String vaultId, String mcpServerUrl) {
        return mapper.existsActiveByVaultIdAndUrl(vaultId, mcpServerUrl);
    }

    @Override
    public int archive(String vaultId, String credentialId, OffsetDateTime archivedAt) {
        return mapper.updateArchivedAt(vaultId, credentialId, archivedAt);
    }

    @Override
    public int delete(String vaultId, String credentialId) {
        // BaseEntity 的 @TableLogic：delete 实为逻辑删（置 is_deleted），历史数据保留
        return mapper.delete(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId)
                .eq(VaultCredentialEntity::getCredentialId, credentialId));
    }

    @Override
    public void deleteByVaultId(String vaultId) {
        // BaseEntity 的 @TableLogic：delete 实为逻辑删（置 is_deleted），历史数据保留
        mapper.delete(Wrappers.<VaultCredentialEntity>lambdaQuery()
                .eq(VaultCredentialEntity::getVaultId, vaultId));
    }
}
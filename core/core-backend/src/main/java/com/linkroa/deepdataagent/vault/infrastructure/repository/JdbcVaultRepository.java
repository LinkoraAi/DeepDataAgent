package com.linkroa.deepdataagent.vault.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.convert.VaultPersistenceConvert;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultEntity;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper.VaultMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 保管库仓储实现（MyBatis-Plus）。
 */
@Repository
public class JdbcVaultRepository implements VaultRepository {

    private final VaultMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 保管库表 Mapper
     */
    public JdbcVaultRepository(VaultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Vault save(Vault vault) {
        VaultEntity entity = VaultPersistenceConvert.INSTANCE.toEntity(vault);
        entity.setId(null);
        mapper.insert(entity);
        return findByVaultId(vault.vaultId()).orElse(vault);
    }

    @Override
    public Optional<Vault> findByVaultId(String vaultId) {
        return Optional.ofNullable(VaultPersistenceConvert.INSTANCE.toDomain(mapper.selectByVaultId(vaultId)));
    }

    @Override
    public Optional<Vault> findByVaultIdForUpdate(String vaultId) {
        return Optional.ofNullable(VaultPersistenceConvert.INSTANCE.toDomain(mapper.selectByVaultIdForUpdate(vaultId)));
    }

    @Override
    public List<Vault> findByIds(Long ownerId, List<String> vaultIds) {
        return mapper.selectByIds(ownerId, vaultIds).stream()
                .map(VaultPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Vault> findByCursor(Long ownerId, VaultListFilter filter, int limit) {
        return mapper.selectByCursor(ownerId, filter, limit).stream()
                .map(VaultPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByFilter(Long ownerId, VaultListFilter filter) {
        return mapper.countByFilter(ownerId, filter);
    }

    @Override
    public int archive(String vaultId, OffsetDateTime archivedAt) {
        return mapper.updateArchivedAt(vaultId, archivedAt);
    }

    @Override
    public void deleteByVaultId(String vaultId) {
        // BaseEntity 的 @TableLogic：delete 实为逻辑删（置 is_deleted），历史数据保留
        mapper.delete(Wrappers.<VaultEntity>lambdaQuery()
                .eq(VaultEntity::getVaultId, vaultId));
    }
}
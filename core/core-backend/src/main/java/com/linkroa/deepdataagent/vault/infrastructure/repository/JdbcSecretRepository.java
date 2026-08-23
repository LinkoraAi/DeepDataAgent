package com.linkroa.deepdataagent.vault.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import com.linkroa.deepdataagent.vault.domain.repository.SecretRepository;
import com.linkroa.deepdataagent.vault.infrastructure.convert.SecretPersistenceConvert;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.SecretEntity;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper.SecretMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 凭证密钥仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcSecretRepository implements SecretRepository {

    private final SecretMapper mapper;

    public JdbcSecretRepository(SecretMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Secret save(Secret secret) {
        SecretEntity entity = SecretPersistenceConvert.INSTANCE.toEntity(secret);
        entity.setId(null);
        mapper.insert(entity);
        return findBySecretId(secret.secretId()).orElse(secret);
    }

    @Override
    public Optional<Secret> findBySecretId(String secretId) {
        return Optional.ofNullable(SecretPersistenceConvert.INSTANCE.toDomain(mapper.selectBySecretId(secretId)));
    }

    @Override
    public Optional<Secret> findBySecretIdForUpdate(String secretId) {
        return Optional.ofNullable(SecretPersistenceConvert.INSTANCE.toDomain(mapper.selectBySecretIdForUpdate(secretId)));
    }

    @Override
    public List<Secret> findByIds(List<String> secretIds) {
        return mapper.selectByIds(secretIds).stream()
                .map(SecretPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Secret> findByPage(int page, int size) {
        return mapper.selectPage((long) Math.max(0, page - 1) * size, size).stream()
                .map(SecretPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countAll() {
        return mapper.countAll();
    }

    @Override
    public void deleteBySecretId(String secretId) {
        mapper.delete(Wrappers.<SecretEntity>lambdaUpdate()
                .eq(e -> e.getSecretId(), secretId));
    }
}
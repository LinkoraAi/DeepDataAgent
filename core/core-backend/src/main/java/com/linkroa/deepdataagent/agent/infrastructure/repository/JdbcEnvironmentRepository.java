package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.EnvironmentPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.EnvironmentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 运行环境仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcEnvironmentRepository implements EnvironmentRepository {

    private final EnvironmentMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 运行环境表 Mapper
     */
    public JdbcEnvironmentRepository(EnvironmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Environment save(Environment environment) {
        EnvironmentEntity entity = EnvironmentPersistenceConvert.INSTANCE.toEntity(environment);
        entity.setId(null);
        mapper.insert(entity);
        return findByEnvironmentId(environment.environmentId()).orElse(environment);
    }

    @Override
    public Environment update(Environment environment) {
        EnvironmentEntity entity = EnvironmentPersistenceConvert.INSTANCE.toEntity(environment);
        mapper.update(entity, Wrappers.<EnvironmentEntity>lambdaUpdate()
                .eq(EnvironmentEntity::getEnvironmentId, environment.environmentId()));
        return findByEnvironmentId(environment.environmentId()).orElse(environment);
    }

    @Override
    public Optional<Environment> findByEnvironmentId(String environmentId) {
        return Optional.ofNullable(EnvironmentPersistenceConvert.INSTANCE.toDomain(mapper.selectByEnvironmentId(environmentId)));
    }

    @Override
    public Optional<Environment> findByNameAndOwnerId(String name, Long ownerId) {
        return Optional.ofNullable(EnvironmentPersistenceConvert.INSTANCE.toDomain(mapper.selectByNameAndOwnerId(name, ownerId)));
    }

    @Override
    public Optional<Environment> findByEnvironmentIdForUpdate(String environmentId) {
        return Optional.ofNullable(EnvironmentPersistenceConvert.INSTANCE.toDomain(mapper.selectByEnvironmentIdForUpdate(environmentId)));
    }

    @Override
    public List<Environment> findByIds(List<String> environmentIds) {
        return mapper.selectByIds(environmentIds).stream()
                .map(EnvironmentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Environment> findByCursor(Long ownerId, EnvironmentListFilter filter, int limit) {
        return mapper.selectByCursor(ownerId, filter, limit).stream()
                .map(EnvironmentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public void deleteByEnvironmentId(String environmentId) {
        mapper.delete(Wrappers.<EnvironmentEntity>lambdaUpdate()
                .eq(EnvironmentEntity::getEnvironmentId, environmentId));
    }
}
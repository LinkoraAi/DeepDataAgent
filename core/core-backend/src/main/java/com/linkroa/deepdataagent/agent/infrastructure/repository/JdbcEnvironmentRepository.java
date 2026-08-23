package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
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
                .eq(e -> e.getEnvironmentId(), environment.environmentId()));
        return findByEnvironmentId(environment.environmentId()).orElse(environment);
    }

    @Override
    public Optional<Environment> findByEnvironmentId(String environmentId) {
        return Optional.ofNullable(EnvironmentPersistenceConvert.INSTANCE.toDomain(mapper.selectByEnvironmentId(environmentId)));
    }

    @Override
    public Optional<Environment> findByName(String name) {
        return Optional.ofNullable(EnvironmentPersistenceConvert.INSTANCE.toDomain(mapper.selectByName(name)));
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
    public List<Environment> findAll() {
        return mapper.selectList(Wrappers.<EnvironmentEntity>lambdaQuery()).stream()
                .map(EnvironmentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Environment> findByPage(int page, int size) {
        return mapper.selectPage((long) Math.max(0, page - 1) * size, size).stream()
                .map(EnvironmentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countAll() {
        return mapper.countAll();
    }

    @Override
    public void deleteByEnvironmentId(String environmentId) {
        mapper.delete(Wrappers.<EnvironmentEntity>lambdaUpdate()
                .eq(e -> e.getEnvironmentId(), environmentId));
    }
}
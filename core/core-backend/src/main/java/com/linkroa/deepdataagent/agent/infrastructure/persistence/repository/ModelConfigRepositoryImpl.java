package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelConfigEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.ModelConfigMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模型配置仓储实现
 * <p>基于 MyBatis-Plus {@link ModelConfigMapper} 实现 {@link ModelConfigRepository} 接口，
 * 负责领域模型 {@link ModelConfig} 与持久化实体 {@link ModelConfigEntity} 之间的双向转换。</p>
 */
@Repository
public class ModelConfigRepositoryImpl implements ModelConfigRepository {

    private final ModelConfigMapper mapper;

    public ModelConfigRepositoryImpl(ModelConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ModelConfig> findById(Long id) {
        ModelConfigEntity entity = mapper.selectById(id);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<ModelConfig> findAllEnabled() {
        return mapper.selectAllEnabled().stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<ModelConfig> findDefault() {
        ModelConfigEntity entity = mapper.selectDefault();
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<ModelConfig> findByProviderName(String providerName) {
        return mapper.selectByProviderName(providerName).stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<ModelConfig> findProviders() {
        return mapper.selectProviders().stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public ModelConfig save(ModelConfig info) {
        ModelConfigEntity entity = toEntity(info);
        if (entity.getId() != null && mapper.selectById(entity.getId()) != null) {
            mapper.updateById(entity);
        } else {
            mapper.insert(entity);
            info.setId(entity.getId());
        }
        return toDomain(mapper.selectById(entity.getId()));
    }

    @Override
    public void update(ModelConfig info) {
        mapper.updateById(toEntity(info));
    }

    @Override
    public void markDeleted(Long id) {
        mapper.markDeleted(id);
    }

    // --- Entity ↔ Domain 转换 ---

    private ModelConfig toDomain(ModelConfigEntity e) {
        ModelConfig info = new ModelConfig();
        info.setId(e.getId());
        info.setProviderDisplayName(e.getProviderDisplayName());
        info.setProviderName(e.getProviderName());
        info.setModelId(e.getModelId());
        info.setApiUrl(e.getApiUrl());
        info.setApiKey(e.getApiKey());
        info.setDefaultModel(e.getIsDefault());
        info.setEnabled(e.getIsEnabled());
        info.setSortOrder(e.getSortOrder());
        info.setCreatedTime(e.getCreatedTime());
        info.setUpdatedTime(e.getUpdatedTime());
        info.setDeleted(e.getIsDeleted());
        return info;
    }

    private ModelConfigEntity toEntity(ModelConfig info) {
        ModelConfigEntity e = new ModelConfigEntity();
        e.setId(info.getId());
        e.setProviderDisplayName(info.getProviderDisplayName());
        e.setProviderName(info.getProviderName());
        e.setModelId(info.getModelId());
        e.setApiUrl(info.getApiUrl());
        e.setApiKey(info.getApiKey());
        e.setIsDefault(info.getDefaultModel());
        e.setIsEnabled(info.getEnabled());
        e.setSortOrder(info.getSortOrder());
        e.setCreatedTime(info.getCreatedTime());
        e.setUpdatedTime(info.getUpdatedTime());
        e.setIsDeleted(info.getDeleted());
        return e;
    }
}
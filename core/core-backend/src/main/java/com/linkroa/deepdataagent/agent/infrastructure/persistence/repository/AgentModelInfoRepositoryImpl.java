package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentModelInfo;
import com.linkroa.deepdataagent.agent.domain.repository.AgentModelInfoRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentModelInfoEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentModelInfoMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AgentModelInfoRepositoryImpl implements AgentModelInfoRepository {

    private final AgentModelInfoMapper mapper;

    public AgentModelInfoRepositoryImpl(AgentModelInfoMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentModelInfo> findById(Long id) {
        AgentModelInfoEntity entity = mapper.selectById(id);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<AgentModelInfo> findAllEnabled() {
        return mapper.selectAllEnabled().stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<AgentModelInfo> findDefault() {
        AgentModelInfoEntity entity = mapper.selectDefault();
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<AgentModelInfo> findByProviderName(String providerName) {
        return mapper.selectByProviderName(providerName).stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<AgentModelInfo> findDistinctProviders() {
        return mapper.selectDistinctProviders().stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public AgentModelInfo save(AgentModelInfo info) {
        AgentModelInfoEntity entity = toEntity(info);
        if (entity.getId() != null && mapper.selectById(entity.getId()) != null) {
            mapper.updateById(entity);
        } else {
            mapper.insert(entity);
            info.setId(entity.getId());
        }
        return toDomain(mapper.selectById(entity.getId()));
    }

    @Override
    public void update(AgentModelInfo info) {
        mapper.updateById(toEntity(info));
    }

    @Override
    public void markDeleted(Long id) {
        mapper.markDeleted(id);
    }

    // --- Entity ↔ Domain 转换 ---

    private AgentModelInfo toDomain(AgentModelInfoEntity e) {
        AgentModelInfo info = new AgentModelInfo();
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

    private AgentModelInfoEntity toEntity(AgentModelInfo info) {
        AgentModelInfoEntity e = new AgentModelInfoEntity();
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

package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentListFilter;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.AgentPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentDefinitionMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent 定义仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private final AgentDefinitionMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper Agent 定义表 Mapper
     */
    public JdbcAgentDefinitionRepository(AgentDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        AgentDefinitionEntity entity = AgentPersistenceConvert.INSTANCE.toEntity(definition);
        entity.setId(null);
        mapper.insert(entity);
        return findByAgentId(definition.agentId()).orElse(definition);
    }

    @Override
    public AgentDefinition update(AgentDefinition definition) {
        AgentDefinitionEntity entity = AgentPersistenceConvert.INSTANCE.toEntity(definition);
        mapper.update(entity, Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .eq(AgentDefinitionEntity::getAgentId, definition.agentId()));
        return findByAgentId(definition.agentId()).orElse(definition);
    }

    @Override
    public Optional<AgentDefinition> findByAgentId(String agentId) {
        return Optional.ofNullable(AgentPersistenceConvert.INSTANCE.toDomain(mapper.selectByAgentId(agentId)));
    }

    @Override
    public Optional<AgentDefinition> findByAgentIdForUpdate(String agentId) {
        return Optional.ofNullable(AgentPersistenceConvert.INSTANCE.toDomain(mapper.selectByAgentIdForUpdate(agentId)));
    }

    @Override
    public List<AgentDefinition> findByCursor(Long ownerId, AgentListFilter filter, int limit) {
        return mapper.selectByCursor(ownerId, filter, limit).stream()
                .map(AgentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public void updateArchivedAt(String agentId, OffsetDateTime archivedAt) {
        mapper.updateArchivedAt(agentId, archivedAt);
    }

    @Override
    public void updateActiveVersion(String agentId, int versionNumber) {
        mapper.updateActiveVersion(agentId, versionNumber);
    }

    @Override
    public void deleteByAgentId(String agentId) {
        mapper.delete(Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .eq(AgentDefinitionEntity::getAgentId, agentId));
    }
}
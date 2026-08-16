package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.AgentPersistenceMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentDefinitionMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Agent 定义仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private final AgentDefinitionMapper mapper;
    private final AgentPersistenceMapper persistenceMapper;

    public JdbcAgentDefinitionRepository(AgentDefinitionMapper mapper, AgentPersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        AgentDefinitionEntity entity = persistenceMapper.toEntity(definition);
        entity.setId(null);
        mapper.insert(entity);
        return findByAgentId(definition.agentId()).orElse(definition);
    }

    @Override
    public AgentDefinition update(AgentDefinition definition) {
        AgentDefinitionEntity entity = persistenceMapper.toEntity(definition);
        mapper.update(entity, Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .eq(e -> e.getAgentId(), definition.agentId()));
        return findByAgentId(definition.agentId()).orElse(definition);
    }

    @Override
    public Optional<AgentDefinition> findByAgentId(String agentId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByAgentId(agentId)));
    }

    @Override
    public Optional<AgentDefinition> findByAgentIdForUpdate(String agentId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByAgentIdForUpdate(agentId)));
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByName(name)));
    }

    @Override
    public List<AgentDefinition> findByCondition(String keyword, boolean includeArchived, int page, int size) {
        return mapper.selectByCondition(
                        keyword,
                        includeArchived,
                        (long) Math.max(0, page - 1) * size,
                        size)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByCondition(String keyword, boolean includeArchived) {
        return mapper.countByCondition(keyword, includeArchived);
    }

    @Override
    public void updateArchived(String agentId, boolean archived) {
        mapper.update(null, Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .set(e -> e.getArchived(), archived)
                .set(e -> e.getArchivedAt(), archived ? OffsetDateTime.now(ZoneId.of("Asia/Shanghai")) : null)
                .eq(e -> e.getAgentId(), agentId));
    }

    @Override
    public void deleteByAgentId(String agentId) {
        mapper.delete(Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .eq(e -> e.getAgentId(), agentId));
    }
}
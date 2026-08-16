package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.AgentPersistenceMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentVersionMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 版本仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcAgentVersionRepository implements AgentVersionRepository {

    private final AgentVersionMapper mapper;
    private final AgentPersistenceMapper persistenceMapper;

    public JdbcAgentVersionRepository(AgentVersionMapper mapper, AgentPersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public AgentVersion save(AgentVersion version) {
        AgentVersionEntity entity = persistenceMapper.toEntity(version);
        entity.setId(null);
        mapper.insert(entity);
        return findByVersionId(version.versionId()).orElse(version);
    }

    @Override
    public Optional<AgentVersion> findByVersionId(String versionId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByVersionId(versionId)));
    }

    @Override
    public Optional<AgentVersion> findByAgentIdAndVersionNumber(String agentId, int versionNumber) {
        return Optional.ofNullable(persistenceMapper.toDomain(
                mapper.selectByAgentIdAndVersionNumber(agentId, versionNumber)));
    }

    @Override
    public List<AgentVersion> listByAgentId(String agentId) {
        return mapper.selectByAgentId(agentId).stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public int findMaxVersionNumber(String agentId) {
        AgentVersionEntity max = mapper.selectMaxVersion(agentId);
        return max != null && max.getVersionNumber() != null ? max.getVersionNumber() : 0;
    }

    @Override
    public long countByModelProfileId(String modelProfileId) {
        Long count = mapper.countByModelProfileId(modelProfileId);
        return count != null ? count : 0;
    }

    @Override
    public void deleteByAgentId(String agentId) {
        mapper.delete(Wrappers.<AgentVersionEntity>lambdaUpdate()
                .eq(e -> e.getAgentId(), agentId));
    }
}
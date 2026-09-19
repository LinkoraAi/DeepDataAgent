package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.AgentPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentVersionMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Agent 版本仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcAgentVersionRepository implements AgentVersionRepository {

    private static final tools.jackson.databind.ObjectMapper OBJECT_MAPPER = new tools.jackson.databind.ObjectMapper();

    private final AgentVersionMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper Agent 版本表 Mapper
     */
    public JdbcAgentVersionRepository(AgentVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentVersion save(AgentVersion version) {
        AgentVersionEntity entity = AgentPersistenceConvert.INSTANCE.toEntity(version);
        entity.setId(null);
        mapper.insert(entity);
        return findByVersionId(version.versionId()).orElse(version);
    }

    @Override
    public Optional<AgentVersion> findByVersionId(String versionId) {
        return Optional.ofNullable(AgentPersistenceConvert.INSTANCE.toDomain(mapper.selectByVersionId(versionId)));
    }

    @Override
    public Optional<AgentVersion> findByAgentIdAndVersionNumber(String agentId, int versionNumber) {
        return Optional.ofNullable(AgentPersistenceConvert.INSTANCE.toDomain(
                mapper.selectByAgentIdAndVersionNumber(agentId, versionNumber)));
    }

    @Override
    public List<AgentVersion> listByAgentId(String agentId) {
        return mapper.selectByAgentId(agentId).stream()
                .map(AgentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<AgentVersion> findByAgentIdCursor(String agentId, Integer cursorVersionNumber, boolean reverse, int limit) {
        return mapper.selectByAgentIdCursor(agentId, cursorVersionNumber, reverse, limit).stream()
                .map(AgentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<AgentVersion> findByAgentIdsAndVersionNumbers(Collection<String> agentIds,
                                                             Collection<Integer> versionNumbers) {
        if (agentIds == null || agentIds.isEmpty() || versionNumbers == null || versionNumbers.isEmpty()) {
            return List.of();
        }
        // Mapper 侧为 (agent_id, version_number) 笛卡尔放宽查询，精确组合过滤由调用方完成
        return mapper.selectByAgentIdsAndVersionNumbers(agentIds, versionNumbers).stream()
                .map(AgentPersistenceConvert.INSTANCE::toDomain)
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
    public long countSkillBindings(String skillId) {
        Long count = mapper.countBySkillBinding(skillContainment(java.util.Map.of("skill_id", skillId)));
        return count != null ? count : 0;
    }

    @Override
    public long countSkillVersionBindings(String skillId, String version) {
        // 钉版绑定保存时已固定版本键：包含判定同时约束 skill_id + version 两键（动态版不落 version 键、不参与计数）
        Long count = mapper.countBySkillBinding(
                skillContainment(java.util.Map.of("skill_id", skillId, "version", version)));
        return count != null ? count : 0;
    }

    /** 构造 skills_json JSONB 包含判定串（转义交由 Jackson 构造，规避注入）。 */
    private static String skillContainment(java.util.Map<String, Object> bindingKeys) {
        try {
            return OBJECT_MAPPER.writeValueAsString(List.of(bindingKeys));
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能绑定引用查询构造失败", e);
        }
    }

    @Override
    public void deleteByAgentId(String agentId) {
        mapper.delete(Wrappers.<AgentVersionEntity>lambdaUpdate()
                .eq(AgentVersionEntity::getAgentId, agentId));
    }
}
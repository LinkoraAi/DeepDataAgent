package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.SkillResourcePersistenceMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.SkillResourceEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.SkillResourceMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 技能资源仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcSkillRepository implements SkillRepository {

    private final SkillResourceMapper mapper;
    private final SkillResourcePersistenceMapper persistenceMapper;

    public JdbcSkillRepository(SkillResourceMapper mapper, SkillResourcePersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public SkillResource save(SkillResource skillResource) {
        SkillResourceEntity entity = persistenceMapper.toEntity(skillResource);
        entity.setId(null);
        mapper.insert(entity);
        return findBySkillIdAndVersion(skillResource.skillId(), skillResource.versionNumber()).orElse(skillResource);
    }

    @Override
    public Optional<SkillResource> findBySkillIdAndVersion(String skillId, int versionNumber) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectBySkillIdAndVersion(skillId, versionNumber)));
    }

    @Override
    public List<SkillResource> listBySkillId(String skillId) {
        return mapper.selectBySkillId(skillId).stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public int findMaxVersionNumber(String skillId) {
        SkillResourceEntity max = mapper.selectMaxVersion(skillId);
        return max != null && max.getVersionNumber() != null ? max.getVersionNumber() : 0;
    }

    @Override
    public Optional<SkillResource> findMaxVersionForUpdate(String skillId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectMaxVersionForUpdate(skillId)));
    }

    @Override
    public List<SkillResource> findLatestByCondition(String keyword, int page, int size) {
        return mapper.selectLatestByCondition(
                        keyword,
                        (long) Math.max(0, page - 1) * size,
                        size)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countSkillsByCondition(String keyword) {
        return mapper.countSkillsByCondition(keyword);
    }

    @Override
    public void deleteBySkillId(String skillId) {
        mapper.delete(Wrappers.<SkillResourceEntity>lambdaUpdate()
                .eq(e -> e.getSkillId(), skillId));
    }
}
package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.ModelProfilePersistenceMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelProfileEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.ModelProfileMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcModelProfileRepository implements ModelProfileRepository {

    private final ModelProfileMapper mapper;
    private final ModelProfilePersistenceMapper persistenceMapper;

    public JdbcModelProfileRepository(ModelProfileMapper mapper, ModelProfilePersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public ModelProfile save(ModelProfile profile) {
        ModelProfileEntity entity = persistenceMapper.toEntity(profile);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findByProfileId(profile.profileId()).orElse(profile);
    }

    @Override
    public ModelProfile update(ModelProfile profile) {
        ModelProfileEntity entity = persistenceMapper.toEntity(profile);
        mapper.update(entity, Wrappers.<ModelProfileEntity>lambdaUpdate()
                .eq(e -> e.getProfileId(), profile.profileId()));
        return findByProfileId(profile.profileId()).orElse(profile);
    }

    @Override
    public Optional<ModelProfile> findByProfileId(String profileId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByProfileId(profileId)));
    }

    @Override
    public Optional<ModelProfile> findByProfileIdForUpdate(String profileId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByProfileIdForUpdate(profileId)));
    }

    @Override
    public Optional<ModelProfile> findByDisplayName(String displayName) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectByDisplayName(displayName)));
    }

    @Override
    public List<ModelProfile> findByCondition(String keyword, ModelProfileStatus status, int page, int size) {
        return mapper.selectByCondition(
                        keyword,
                        status != null ? status.name() : null,
                        (long) Math.max(0, page - 1) * size,
                        size)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByCondition(String keyword, ModelProfileStatus status) {
        return mapper.countByCondition(keyword, status != null ? status.name() : null);
    }

    @Override
    public void updateStatus(String profileId, ModelProfileStatus status) {
        mapper.updateStatus(profileId, status.name());
    }

    @Override
    public void deleteByProfileId(String profileId) {
        // 逻辑删除（is_deleted 置 1）由 MyBatis-Plus @TableLogic 内建实现
        mapper.delete(Wrappers.<ModelProfileEntity>lambdaUpdate()
                .eq(e -> e.getProfileId(), profileId));
    }
}
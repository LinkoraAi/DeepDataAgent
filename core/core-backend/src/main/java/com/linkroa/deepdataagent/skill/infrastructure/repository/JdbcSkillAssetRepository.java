package com.linkroa.deepdataagent.skill.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillListFilter;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.repository.SkillAssetRepository;
import com.linkroa.deepdataagent.skill.infrastructure.convert.SkillPersistenceConvert;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.entity.SkillContentVersionEntity;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.entity.SkillEntity;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper.SkillContentVersionMapper;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper.SkillMapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能资产仓储实现（MyBatis-Plus，同时管理 {@code skill} 与 {@code skill_content_version} 两表）。
 * <p>壳对象的业务元数据由 {@link SkillPersistenceConvert} 收敛 JSONB 形态；内容版本的资源清单
 * （相对路径 → 字节长度）由本仓储经 JSON 工具收敛 {@code Map<String, Long>} ⇄ 字符串双向映射。
 * 技能包正文与资源文件内容不入库（落对象存储资产目录）。</p>
 */
@Repository
public class JdbcSkillAssetRepository implements SkillAssetRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> RESOURCE_TYPE = new TypeReference<>() {
    };

    @Resource
    private SkillMapper skillMapper;
    @Resource
    private SkillContentVersionMapper versionMapper;

    @Override
    public SkillAsset save(SkillAsset asset, SkillVersion version) {
        SkillEntity entity = SkillPersistenceConvert.INSTANCE.toEntity(asset);
        entity.setId(null);
        // 创建即首版：壳的最新版本指针由首个版本键推进（契约「latest_version 置为该版本键」）
        entity.setLatestVersion(version.version());
        skillMapper.insert(entity);
        versionMapper.insert(buildVersionEntity(version));
        return findBySkillId(asset.skillId()).orElse(asset);
    }

    @Override
    public SkillAsset appendVersion(SkillAsset asset, SkillVersion version) {
        updateAssetRow(asset);
        versionMapper.insert(buildVersionEntity(version));
        return findBySkillId(asset.skillId()).orElse(asset);
    }

    @Override
    public SkillAsset updateLatestVersion(SkillAsset asset) {
        updateAssetRow(asset);
        return findBySkillId(asset.skillId()).orElse(asset);
    }

    @Override
    public Optional<SkillAsset> findBySkillId(String skillId) {
        return Optional.ofNullable(SkillPersistenceConvert.INSTANCE.toDomain(skillMapper.selectBySkillId(skillId)));
    }

    @Override
    public List<SkillAsset> findByCursor(Long ownerId, SkillListFilter filter, int limit) {
        // 游标行位点（created_at + 数据库主键 id）由应用层解析 after_id/before_id 后装配
        return skillMapper.selectByCursor(ownerId, filter, limit).stream()
                .map(SkillPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public void deleteBySkillId(String skillId) {
        skillMapper.delete(Wrappers.<SkillEntity>lambdaQuery().eq(SkillEntity::getSkillId, skillId));
    }

    @Override
    public List<SkillVersion> listVersions(String skillId) {
        return versionMapper.selectBySkillId(skillId).stream()
                .map(JdbcSkillAssetRepository::toVersion)
                .toList();
    }

    @Override
    public Optional<SkillVersion> findVersion(String skillId, String version) {
        return Optional.ofNullable(versionMapper.selectBySkillIdAndVersion(skillId, version))
                .map(JdbcSkillAssetRepository::toVersion);
    }

    @Override
    public Optional<SkillVersion> findLatestVersion(String skillId) {
        return Optional.ofNullable(versionMapper.selectLatestVersion(skillId))
                .map(JdbcSkillAssetRepository::toVersion);
    }

    @Override
    public Optional<SkillVersion> findFirstVersion(String skillId) {
        return Optional.ofNullable(versionMapper.selectFirstVersion(skillId))
                .map(JdbcSkillAssetRepository::toVersion);
    }

    @Override
    public void deleteVersion(String skillId, String version) {
        // @TableLogic 逻辑删除（is_deleted=1），版本台账行保留可追溯、查询自动不可见
        versionMapper.delete(Wrappers.<SkillContentVersionEntity>lambdaQuery()
                .eq(SkillContentVersionEntity::getSkillId, skillId)
                .eq(SkillContentVersionEntity::getVersion, version));
    }

    /**
     * 更新壳行（`update(entity, wrapper)` 形态以触发审计字段自动填充；实体主键不参与 SET）。
     *
     * @param asset 指针更新后的技能壳
     */
    private void updateAssetRow(SkillAsset asset) {
        skillMapper.update(SkillPersistenceConvert.INSTANCE.toEntity(asset),
                Wrappers.<SkillEntity>lambdaUpdate().eq(SkillEntity::getSkillId, asset.skillId()));
    }

    /** 领域版本 → 持久化实体（资源清单序列化为 JSON 文本）。 */
    private static SkillContentVersionEntity buildVersionEntity(SkillVersion version) {
        SkillContentVersionEntity entity = new SkillContentVersionEntity();
        entity.setVersionId(version.id());
        entity.setSkillId(version.skillId());
        entity.setVersion(version.version());
        entity.setName(version.name());
        entity.setDescription(version.description());
        entity.setDirectory(version.directory());
        entity.setContentSha256(version.contentSha256());
        entity.setContentSize(version.contentSize());
        entity.setResources(writeResources(version.resources()));
        return entity;
    }

    /** 持久化实体 → 领域版本（资源清单反序列化；创建时间取行 created_at）。 */
    private static SkillVersion toVersion(SkillContentVersionEntity entity) {
        return SkillVersion.restore(
                entity.getVersionId(),
                entity.getSkillId(),
                entity.getVersion(),
                entity.getName(),
                entity.getDescription(),
                entity.getDirectory(),
                entity.getContentSha256(),
                entity.getContentSize() == null ? 0L : entity.getContentSize(),
                readResources(entity.getResources()),
                entity.getCreatedAt());
    }

    private static String writeResources(Map<String, Long> resources) {
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(resources);
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能资源清单序列化失败", e);
        }
    }

    private static Map<String, Long> readResources(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Long> parsed = OBJECT_MAPPER.readValue(json, RESOURCE_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能资源清单反序列化失败", e);
        }
    }
}
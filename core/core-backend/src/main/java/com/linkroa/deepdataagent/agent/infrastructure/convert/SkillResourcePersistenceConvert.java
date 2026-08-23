package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.SkillResourceManifest;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.SkillResourceEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 技能资源领域对象 ⇄ 持久化实体转换器。
 * <p>skillType 以整型码值存储、storageType/status 以字符串存储，均在 default 方法中手工映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillResourcePersistenceConvert {

    SkillResourcePersistenceConvert INSTANCE = Mappers.getMapper(SkillResourcePersistenceConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    default SkillResource toDomain(SkillResourceEntity entity) {
        if (entity == null) {
            return null;
        }
        return SkillResource.restore(
                entity.getId(),
                entity.getSkillId(),
                entity.getVersionNumber() != null ? entity.getVersionNumber() : 0,
                entity.getName(),
                entity.getDescription(),
                entity.getSkillType() != null ? SkillType.fromCode(entity.getSkillType()) : SkillType.CUSTOM,
                entity.getStorageType() != null && !entity.getStorageType().isBlank()
                        ? SkillStorageType.valueOf(entity.getStorageType()) : SkillStorageType.LOCAL_FILE,
                entity.getStorageKey(),
                entity.getContentSha256(),
                entity.getContentSize() != null ? entity.getContentSize() : 0,
                manifestFromJson(entity.getResourceManifest()),
                entity.getStatus() != null && !entity.getStatus().isBlank()
                        ? SkillStatus.valueOf(entity.getStatus()) : SkillStatus.ACTIVE,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    default SkillResourceEntity toEntity(SkillResource skill) {
        if (skill == null) {
            return null;
        }
        SkillResourceEntity entity = new SkillResourceEntity();
        entity.setId(skill.id());
        entity.setSkillId(skill.skillId());
        entity.setVersionNumber(skill.versionNumber());
        entity.setName(skill.name());
        entity.setDescription(skill.description());
        entity.setSkillType(skill.skillType() != null ? skill.skillType().getCode() : SkillType.CUSTOM.getCode());
        entity.setStorageType(skill.storageType() != null ? skill.storageType().name() : SkillStorageType.LOCAL_FILE.name());
        entity.setStorageKey(skill.storageKey());
        entity.setContentSha256(skill.contentSha256());
        entity.setContentSize(skill.contentSize());
        entity.setResourceManifest(manifestToJson(skill.resources()));
        entity.setStatus(skill.status() != null ? skill.status().name() : SkillStatus.ACTIVE.name());
        return entity;
    }

    default String manifestToJson(SkillResourceManifest manifest) {
        if (manifest == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(manifest);
        } catch (JacksonException e) {
            throw new IllegalStateException("技能结构化资源序列化失败", e);
        }
    }

    default SkillResourceManifest manifestFromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return SkillResourceManifest.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, SkillResourceManifest.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("技能结构化资源解析失败", e);
        }
    }
}
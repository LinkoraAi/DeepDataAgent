package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.SkillResourceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 技能资源领域对象 ⇄ 持久化实体转换器。
 * <p>skillType 以整型码值存储、storageType/status 以字符串存储，均在 default 方法中手工映射。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillResourcePersistenceMapper {

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
        entity.setStatus(skill.status() != null ? skill.status().name() : SkillStatus.ACTIVE.name());
        return entity;
    }
}
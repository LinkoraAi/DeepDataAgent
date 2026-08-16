package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelProfileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 模型配置领域对象 ⇄ 持久化实体转换器。
 * <p>枚举字段（apiFormat / status）在实体侧以字符串存储、modelType 以整型码值存储，
 * 均在 default 方法中手工映射；加密凭证以密文 String 透传，转换层不感知明文。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ModelProfilePersistenceMapper {

    default ModelProfile toDomain(ModelProfileEntity entity) {
        if (entity == null) {
            return null;
        }
        return ModelProfile.restore(
                entity.getProfileId(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getApiFormat() != null && !entity.getApiFormat().isBlank()
                        ? ApiFormat.valueOf(entity.getApiFormat()) : null,
                entity.getApiEndpointUrl(),
                entity.getModelName(),
                entity.getEncryptedCredential(),
                entity.getModelSeries(),
                entity.getContextWindowInput(),
                entity.getContextWindowOutput(),
                entity.getToolCallRounds(),
                entity.getModelType() != null ? ModelType.fromCode(entity.getModelType()) : null,
                entity.getVectorDimension(),
                entity.getStatus() != null && !entity.getStatus().isBlank()
                        ? ModelProfileStatus.valueOf(entity.getStatus()) : ModelProfileStatus.ENABLED,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    default ModelProfileEntity toEntity(ModelProfile profile) {
        if (profile == null) {
            return null;
        }
        ModelProfileEntity entity = new ModelProfileEntity();
        entity.setProfileId(profile.profileId());
        entity.setDisplayName(profile.displayName());
        entity.setDescription(profile.description());
        entity.setApiFormat(profile.apiFormat() != null ? profile.apiFormat().name() : null);
        entity.setApiEndpointUrl(profile.apiEndpointUrl());
        entity.setModelName(profile.modelName());
        entity.setEncryptedCredential(profile.encryptedCredential());
        entity.setModelSeries(profile.modelSeries());
        entity.setContextWindowInput(profile.contextWindowInput());
        entity.setContextWindowOutput(profile.contextWindowOutput());
        entity.setToolCallRounds(profile.toolCallRounds());
        entity.setModelType(profile.modelType() != null ? profile.modelType().getCode() : null);
        entity.setVectorDimension(profile.vectorDimension());
        entity.setStatus(profile.status() != null ? profile.status().name() : null);
        return entity;
    }
}
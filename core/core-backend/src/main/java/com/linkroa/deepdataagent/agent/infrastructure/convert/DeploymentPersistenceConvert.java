package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 调度器 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>schedule 为值对象 ⇄ JSONB 文本（{@link DeploymentSchedule#toJson()} /
 * {@link DeploymentSchedule#fromJson(String)}），status 为领域枚举 ⇄ 小写值字符串，
 * vaultIds 为 {@code List<String>} ⇄ JSONB 数组文本，显式 default 方法承载映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentPersistenceConvert {

    DeploymentPersistenceConvert INSTANCE = Mappers.getMapper(DeploymentPersistenceConvert.class);

    /** JSON 工具（vaultIds 数组文本 ⇄ 列表解析） */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** vaultIds 反序列化目标类型 */
    TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 领域模型 → 持久化实体。
     */
    default DeploymentEntity toEntity(Deployment deployment) {
        if (deployment == null) {
            return null;
        }
        DeploymentEntity entity = new DeploymentEntity();
        entity.setId(deployment.id());
        entity.setDeploymentId(deployment.deploymentId());
        entity.setName(deployment.name());
        entity.setDescription(deployment.description());
        entity.setAgentId(deployment.agentId());
        entity.setAgentVersion(deployment.agentVersion());
        entity.setEnvironmentId(deployment.environmentId());
        entity.setEnvironmentVariables(deployment.environmentVariables());
        entity.setResources(deployment.resources());
        entity.setVaultIds(toJsonArrayLiteral(deployment.vaultIds()));
        entity.setInitialEvents(deployment.initialEvents());
        entity.setMetadata(deployment.metadata());
        entity.setSchedule(deployment.schedule() == null ? null : deployment.schedule().toJson());
        entity.setNextRunAt(deployment.nextRunAt());
        entity.setWebhookToken(deployment.webhookToken());
        entity.setStatus(deployment.status().getValue());
        entity.setPausedReason(deployment.pausedReason());
        entity.setLastRunAt(deployment.lastRunAt());
        entity.setLastSessionId(deployment.lastSessionId());
        entity.setLastStatus(deployment.lastStatus());
        entity.setOwnerId(deployment.ownerId());
        entity.setArchivedAt(deployment.archivedAt());
        entity.setCreatedAt(deployment.createdAt());
        entity.setUpdatedAt(deployment.updatedAt());
        entity.setCreatedBy(deployment.createdBy());
        entity.setUpdatedBy(deployment.updatedBy());
        return entity;
    }

    /**
     * 持久化实体 → 领域模型。
     */
    default Deployment toDomain(DeploymentEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Deployment(
                entity.getId(),
                entity.getDeploymentId(),
                entity.getName(),
                entity.getDescription(),
                entity.getAgentId(),
                entity.getAgentVersion(),
                entity.getEnvironmentId(),
                entity.getEnvironmentVariables(),
                entity.getResources(),
                fromJsonArrayLiteral(entity.getVaultIds()),
                entity.getInitialEvents(),
                entity.getMetadata(),
                DeploymentSchedule.fromJson(entity.getSchedule()),
                entity.getNextRunAt(),
                entity.getWebhookToken(),
                StringUtils.isBlank(entity.getStatus()) ? null : DeploymentStatus.fromValue(entity.getStatus()),
                entity.getPausedReason(),
                entity.getLastRunAt(),
                entity.getLastSessionId(),
                entity.getLastStatus(),
                entity.getOwnerId(),
                entity.getArchivedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    /**
     * 字符串列表 → JSON 数组文本（null / 空列表归一为 {@code "[]"}）。
     */
    default String toJsonArrayLiteral(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("保管库ID列表序列化失败", e);
        }
    }

    /**
     * JSON 数组文本 → 字符串列表（空白归一为空列表）。
     */
    default List<String> fromJsonArrayLiteral(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("保管库ID列表反序列化失败: " + e.getMessage(), e);
        }
    }
}

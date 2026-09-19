package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentRunEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 调度运行记录 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>triggerKind / status 为领域枚举 ⇄ 小写值字符串，显式 default 方法承载映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentRunPersistenceConvert {

    DeploymentRunPersistenceConvert INSTANCE = Mappers.getMapper(DeploymentRunPersistenceConvert.class);

    /**
     * 领域模型 → 持久化实体。
     */
    default DeploymentRunEntity toEntity(DeploymentRun run) {
        if (run == null) {
            return null;
        }
        DeploymentRunEntity entity = new DeploymentRunEntity();
        entity.setId(run.id());
        entity.setRunId(run.runId());
        entity.setDeploymentId(run.deploymentId());
        entity.setSessionId(run.sessionId());
        entity.setTriggerKind(run.triggerKind().getValue());
        entity.setStatus(run.status().getValue());
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        entity.setCreatedAt(run.createdAt());
        entity.setUpdatedAt(run.updatedAt());
        return entity;
    }

    /**
     * 持久化实体 → 领域模型。
     */
    default DeploymentRun toDomain(DeploymentRunEntity entity) {
        if (entity == null) {
            return null;
        }
        return new DeploymentRun(
                entity.getId(),
                entity.getRunId(),
                entity.getDeploymentId(),
                entity.getSessionId(),
                toTriggerType(entity.getTriggerKind()),
                toRunStatus(entity.getStatus()),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * 触发方式 → 小写字符串（default 辅助方法）。
     */
    default String toTriggerKindValue(DeploymentTriggerType type) {
        return type == null ? null : type.getValue();
    }

    /**
     * 小写字符串 → 触发方式（default 辅助方法；未知值由领域不变量兜住）。
     */
    default DeploymentTriggerType toTriggerType(String value) {
        return StringUtils.isBlank(value) ? null : DeploymentTriggerType.fromValue(value);
    }

    /**
     * 运行状态 → 小写字符串（default 辅助方法）。
     */
    default String toRunStatusValue(DeploymentRunStatus status) {
        return status == null ? null : status.getValue();
    }

    /**
     * 小写字符串 → 运行状态（default 辅助方法）。
     */
    default DeploymentRunStatus toRunStatus(String value) {
        return StringUtils.isBlank(value) ? null : DeploymentRunStatus.fromValue(value);
    }
}

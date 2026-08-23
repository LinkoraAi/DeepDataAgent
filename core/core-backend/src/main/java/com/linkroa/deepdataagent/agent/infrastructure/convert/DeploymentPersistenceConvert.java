package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 部署 ⇄ 持久化实体转换器（MapStruct）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentPersistenceConvert {

    DeploymentPersistenceConvert INSTANCE = Mappers.getMapper(DeploymentPersistenceConvert.class);

    DeploymentEntity toEntity(Deployment deployment);

    Deployment toDomain(DeploymentEntity entity);
}
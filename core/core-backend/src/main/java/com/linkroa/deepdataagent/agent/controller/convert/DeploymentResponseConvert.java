package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 部署 → 响应 DTO 转换器
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeploymentResponseConvert {

    DeploymentResponseConvert INSTANCE = Mappers.getMapper(DeploymentResponseConvert.class);

    DeploymentResponse toResponse(Deployment deployment);
}
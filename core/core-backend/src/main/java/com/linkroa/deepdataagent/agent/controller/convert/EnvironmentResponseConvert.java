package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.controller.response.SandboxSpecResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 运行环境 → 响应 DTO 转换器
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EnvironmentResponseConvert {

    EnvironmentResponseConvert INSTANCE = Mappers.getMapper(EnvironmentResponseConvert.class);

    EnvironmentResponse toResponse(Environment environment);

    SandboxSpecResponse toSpecResponse(SandboxSpec sandboxSpec);
}
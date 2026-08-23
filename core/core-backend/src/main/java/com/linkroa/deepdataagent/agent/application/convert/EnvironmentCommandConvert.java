package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.SandboxSpecRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 运行环境请求转换器（Request → Command）。
 */
@Mapper
public interface EnvironmentCommandConvert {

    EnvironmentCommandConvert INSTANCE = Mappers.getMapper(EnvironmentCommandConvert.class);

    default CreateEnvironmentCommand toCreateCommand(CreateEnvironmentRequest request) {
        return new CreateEnvironmentCommand(
                request.name(),
                parseType(request.type()),
                toSandboxSpec(request.sandboxSpec())
        );
    }

    default UpdateEnvironmentCommand toUpdateCommand(String environmentId, UpdateEnvironmentRequest request) {
        return new UpdateEnvironmentCommand(
                environmentId,
                request.name(),
                parseType(request.type()),
                toSandboxSpec(request.sandboxSpec())
        );
    }

    private static EnvironmentType parseType(String type) {
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("环境类型不能为空");
        }
        try {
            return EnvironmentType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("环境类型本期仅支持 LOCAL");
        }
    }

    private static SandboxSpec toSandboxSpec(SandboxSpecRequest request) {
        return SandboxSpec.create(
                request.image(),
                request.memoryMb(),
                request.cpu(),
                request.workspaceMode(),
                request.timeoutSeconds()
        );
    }
}
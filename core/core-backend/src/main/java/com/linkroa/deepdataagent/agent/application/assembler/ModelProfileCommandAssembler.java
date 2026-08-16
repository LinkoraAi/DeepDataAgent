package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelProfileRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 模型配置请求装配器（Request → Command）
 */
@Component
public class ModelProfileCommandAssembler {

    public CreateModelProfileCommand toCreateCommand(CreateModelProfileRequest request) {
        return new CreateModelProfileCommand(
                request.displayName(),
                request.description(),
                parseApiFormat(request.apiFormat()),
                request.apiEndpointUrl(),
                request.modelName(),
                request.credential(),
                request.modelSeries(),
                request.contextWindowInput(),
                request.contextWindowOutput(),
                request.toolCallRounds() != null ? request.toolCallRounds() : 999999,
                parseModelType(request.modelType()),
                request.vectorDimension()
        );
    }

    public UpdateModelProfileCommand toUpdateCommand(String profileId, UpdateModelProfileRequest request) {
        return new UpdateModelProfileCommand(
                profileId,
                request.displayName(),
                request.description(),
                parseApiFormat(request.apiFormat()),
                request.apiEndpointUrl(),
                request.modelName(),
                // null 表示保留原值（接口层约定），空串表示清空
                request.credential(),
                request.modelSeries(),
                request.contextWindowInput(),
                request.contextWindowOutput(),
                request.toolCallRounds() != null ? request.toolCallRounds() : 999999,
                parseModelType(request.modelType()),
                request.vectorDimension()
        );
    }

    private ApiFormat parseApiFormat(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return ApiFormat.valueOf(value);
    }

    private ModelType parseModelType(Integer code) {
        if (code == null) {
            return ModelType.CHAT;
        }
        return ModelType.fromCode(code);
    }
}
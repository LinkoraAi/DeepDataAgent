package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelProfileRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 模型配置请求转换器（Request → Command）。
 */
@Mapper
public interface ModelProfileCommandConvert {

    ModelProfileCommandConvert INSTANCE = Mappers.getMapper(ModelProfileCommandConvert.class);

    default CreateModelProfileCommand toCreateCommand(CreateModelProfileRequest request) {
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

    default UpdateModelProfileCommand toUpdateCommand(String profileId, UpdateModelProfileRequest request) {
        return new UpdateModelProfileCommand(
                profileId,
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

    private static ApiFormat parseApiFormat(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return ApiFormat.valueOf(value);
    }

    private static ModelType parseModelType(Integer code) {
        if (code == null) {
            return ModelType.CHAT;
        }
        return ModelType.fromCode(code);
    }
}
package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.application.command.CreateSecretCommand;
import com.linkroa.deepdataagent.vault.controller.request.CreateSecretRequest;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 密钥请求转换器（Request → Command）。
 */
@Mapper
public interface SecretCommandConvert {

    SecretCommandConvert INSTANCE = Mappers.getMapper(SecretCommandConvert.class);

    default CreateSecretCommand toCreateCommand(CreateSecretRequest request) {
        return new CreateSecretCommand(request.name(), request.value());
    }
}
package com.linkroa.deepdataagent.auth.application.convert;

import com.linkroa.deepdataagent.auth.application.command.LoginUserCommand;
import com.linkroa.deepdataagent.auth.application.command.RegisterUserCommand;
import com.linkroa.deepdataagent.auth.controller.request.LoginRequest;
import com.linkroa.deepdataagent.auth.controller.request.RegisterRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 认证请求转换器（Request → Command）。
 */
@Mapper
public interface AuthCommandConvert {

    AuthCommandConvert INSTANCE = Mappers.getMapper(AuthCommandConvert.class);

    @Mapping(source = "password", target = "rawPassword")
    RegisterUserCommand toRegisterCommand(RegisterRequest request);

    @Mapping(source = "password", target = "rawPassword")
    LoginUserCommand toLoginCommand(LoginRequest request);
}
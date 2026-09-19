package com.linkroa.deepdataagent.auth.controller.convert;

import com.linkroa.deepdataagent.auth.application.service.LoginResult;
import com.linkroa.deepdataagent.auth.controller.response.LoginResponse;
import com.linkroa.deepdataagent.auth.controller.response.UserResponse;
import com.linkroa.deepdataagent.auth.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 认证领域 / 登录结果 → 响应 DTO 转换器。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AuthResponseConvert {

    AuthResponseConvert INSTANCE = Mappers.getMapper(AuthResponseConvert.class);

    UserResponse toUserResponse(User user);

    LoginResponse toLoginResponse(LoginResult result);
}
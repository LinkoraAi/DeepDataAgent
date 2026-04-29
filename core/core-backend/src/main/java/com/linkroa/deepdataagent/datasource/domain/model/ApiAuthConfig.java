package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import org.apache.commons.lang3.StringUtils;

/**
 * API认证配置值对象
 */
public record ApiAuthConfig(
        ApiAuthType authType,
        String username,
        String password,
        String token
) {
    public ApiAuthConfig {
        if (authType == null) {
            throw new IllegalArgumentException("认证类型不能为空");
        }
        
        switch (authType) {
            case BASIC_AUTH -> {
                if (StringUtils.isBlank(username)) {
                    throw new IllegalArgumentException("Basic认证需要提供用户名");
                }
                if (StringUtils.isBlank(password)) {
                    throw new IllegalArgumentException("Basic认证需要提供密码");
                }
            }
            case BEARER_TOKEN -> {
                if (StringUtils.isBlank(token)) {
                    throw new IllegalArgumentException("Token认证需要提供访问令牌");
                }
            }
            case NO_AUTH -> {
            }
        }
    }
}

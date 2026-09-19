package com.linkroa.deepdataagent.auth.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 */
public record LoginRequest(

        @NotBlank(message = "邮箱不能为空")
        String email,

        @NotBlank(message = "密码不能为空")
        @Size(max = 72, message = "密码长度不能超过 72 位")
        String password
) {
}
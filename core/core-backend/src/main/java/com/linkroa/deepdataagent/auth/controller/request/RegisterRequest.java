package com.linkroa.deepdataagent.auth.controller.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 */
public record RegisterRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式非法")
        String email,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度须在 8-72 位之间")
        String password
) {
}
package com.linkroa.deepdataagent.vault.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建密钥请求
 */
public record CreateSecretRequest(

        @NotBlank(message = "密钥名称不能为空")
        @Size(max = 255, message = "密钥名称不能超过255个字符")
        String name,

        @NotBlank(message = "密钥值不能为空")
        String value
) {
}
package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新运行环境请求（全量替换）
 */
public record UpdateEnvironmentRequest(

        @NotBlank(message = "运行环境名称不能为空")
        @Size(max = 64, message = "运行环境名称不能超过64个字符")
        String name,

        @NotBlank(message = "环境类型不能为空")
        String type,

        @NotNull(message = "沙箱规格不能为空")
        @Valid
        SandboxSpecRequest sandboxSpec
) {
}
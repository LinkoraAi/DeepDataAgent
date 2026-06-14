package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 添加模型配置请求
 */
public record AddModelConfigRequest(
    @NotBlank(message = "配置名称不能为空")
    String name,

    @NotNull(message = "模板 ID 不能为空")
    Long templateId,

    @NotBlank(message = "API Key 不能为空")
    String apiKey,

    Double temperature,

    String description,

    Boolean setDefault
) {}

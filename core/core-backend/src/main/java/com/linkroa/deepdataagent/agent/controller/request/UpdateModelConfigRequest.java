package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 更新模型配置请求
 */
public record UpdateModelConfigRequest(
    @NotNull(message = "配置 ID 不能为空")
    Long id,
    String name,
    String apiKey,
    Double temperature,
    String description
) {}

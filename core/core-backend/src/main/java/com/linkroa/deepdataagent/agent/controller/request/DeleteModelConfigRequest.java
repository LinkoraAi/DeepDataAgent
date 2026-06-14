package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 删除模型配置请求 DTO
 */
public record DeleteModelConfigRequest(
    @NotNull(message = "配置 ID 不能为空")
    Long id
) {}

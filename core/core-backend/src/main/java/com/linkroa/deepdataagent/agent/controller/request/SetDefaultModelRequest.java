package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 设置默认模型请求 DTO
 */
public record SetDefaultModelRequest(
    @NotNull(message = "配置 ID 不能为空")
    Long id
) {}

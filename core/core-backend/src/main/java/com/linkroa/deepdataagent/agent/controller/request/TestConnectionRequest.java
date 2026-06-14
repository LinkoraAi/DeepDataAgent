package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 测试连接请求 DTO
 */
public record TestConnectionRequest(
    @NotNull(message = "配置 ID 不能为空")
    Long id
) {}

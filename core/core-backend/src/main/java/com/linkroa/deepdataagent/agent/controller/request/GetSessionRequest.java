package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 获取会话详情请求 DTO
 */
public record GetSessionRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId
) {}

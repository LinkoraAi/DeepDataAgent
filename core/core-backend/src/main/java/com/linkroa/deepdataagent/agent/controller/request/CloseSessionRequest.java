package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 关闭会话请求 DTO
 */
public record CloseSessionRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId
) {}

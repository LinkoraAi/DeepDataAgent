package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 数据分析停止请求 DTO
 */
public record StopAnalysisRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId
) {}
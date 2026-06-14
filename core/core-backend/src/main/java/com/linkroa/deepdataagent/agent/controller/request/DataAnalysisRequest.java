package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 数据分析请求 DTO
 */
public record DataAnalysisRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,
    @NotNull(message = "模型配置 ID 不能为空")
    Long modelConfigId,
    @NotBlank(message = "数据源 ID 不能为空")
    String connectionId,
    @NotBlank(message = "用户问题不能为空")
    String userQuestion
) {}

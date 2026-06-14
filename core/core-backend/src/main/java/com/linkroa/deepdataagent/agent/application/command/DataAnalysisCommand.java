package com.linkroa.deepdataagent.agent.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 数据分析命令
 */
public record DataAnalysisCommand(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,
    @NotNull(message = "模型配置 ID 不能为空")
    Long modelConfigId,
    @NotBlank(message = "数据源 ID 不能为空")
    String connectionId,
    @NotBlank(message = "用户问题不能为空")
    String userQuestion
) {}

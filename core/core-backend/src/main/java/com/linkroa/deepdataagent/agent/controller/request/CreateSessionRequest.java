package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求 DTO
 */
public record CreateSessionRequest(
    Long userId,

    @NotNull(message = "数据源 ID 不能为空")
    Long datasourceId,

    @NotNull(message = "模型配置 ID 不能为空")
    Long modelConfigId,

    @Size(max = 5000, message = "用户问题长度不能超过 5000 字符")
    String userQuestion
) {}

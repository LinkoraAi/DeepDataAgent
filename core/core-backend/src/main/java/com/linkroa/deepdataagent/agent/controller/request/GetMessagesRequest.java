package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 查询会话消息请求 DTO
 */
public record GetMessagesRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,

    @NotNull(message = "每页数量不能为空")
    Integer limit,

    @NotNull(message = "偏移量不能为空")
    Integer offset
) {}

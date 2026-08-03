package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 查询会话消息请求 DTO
 * <p>limit 语义为"轮次数"（可选，默认 5）；beforeDialogueId 为轮次游标（可选，
 * null 表示取最新轮次，非空表示取 id 更小的更早轮次）。</p>
 */
public record GetMessagesRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,

    Integer limit,

    Long beforeDialogueId
) {}

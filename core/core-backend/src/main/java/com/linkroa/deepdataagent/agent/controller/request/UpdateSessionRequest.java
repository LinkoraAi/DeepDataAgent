package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新会话请求 DTO
 * <p>目前仅支持更新会话标题。</p>
 */
public record UpdateSessionRequest(
    @NotBlank(message = "会话 ID 不能为空")
    String sessionId,

    @NotBlank(message = "会话标题不能为空")
    String title
) {}

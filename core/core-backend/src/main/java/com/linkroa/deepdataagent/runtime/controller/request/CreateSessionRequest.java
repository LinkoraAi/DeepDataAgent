package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建 Agent 会话请求。
 *
 * @param userId       用户 ID
 * @param agentId      Agent 业务 ID
 * @param agentVersion Agent 版本
 * @param title        会话标题（可空）
 * @param metadata     会话元数据（可空，JSON 文本）
 */
public record CreateSessionRequest(
        @NotBlank(message = "用户ID不能为空")
        String userId,

        @NotBlank(message = "AgentID不能为空")
        String agentId,

        @NotBlank(message = "Agent版本不能为空")
        String agentVersion,

        String title,
        String metadata
) {
}
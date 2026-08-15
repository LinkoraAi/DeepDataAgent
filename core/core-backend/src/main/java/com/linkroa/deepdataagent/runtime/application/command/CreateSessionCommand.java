package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 创建 Agent 会话命令。
 *
 * @param userId       用户 ID
 * @param agentId      Agent 业务 ID
 * @param agentVersion Agent 版本
 * @param title        会话标题（可空）
 * @param metadata     会话元数据（可空，JSON 文本）
 */
public record CreateSessionCommand(
        String userId,
        String agentId,
        String agentVersion,
        String title,
        String metadata
) {

    public CreateSessionCommand {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("AgentID不能为空");
        }
        if (StringUtils.isBlank(agentVersion)) {
            throw new IllegalArgumentException("Agent版本不能为空");
        }
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("会话标题长度不能超过255");
        }
    }
}
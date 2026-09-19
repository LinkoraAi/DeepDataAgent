package com.linkroa.deepdataagent.agent.application.command;

/**
 * 更新 Agent 定义命令（OCC 乐观并发：{@code version} 必须匹配当前 latest_version）。
 *
 * @param agentId     Agent 业务 ID
 * @param name        新名称（null = 沿用现名）
 * @param description 新描述（null = 沿用现描述）
 * @param version     客户端持有的当前版本号（不匹配 → 409 conflict_error）
 */
public record UpdateAgentCommand(
        String agentId,
        String name,
        String description,
        int version
) {
}

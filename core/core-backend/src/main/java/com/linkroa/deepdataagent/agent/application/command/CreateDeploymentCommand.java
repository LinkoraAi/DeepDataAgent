package com.linkroa.deepdataagent.agent.application.command;

/**
 * 创建部署命令（激活 / 回滚目标版本）
 *
 * @param agentId       Agent 业务 ID
 * @param versionNumber 激活 / 回滚的目标版本号
 */
public record CreateDeploymentCommand(
        String agentId,
        int versionNumber
) {
}
package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;

import java.util.List;

/**
 * 创建调度器命令。
 *
 * <p>触发配置语义：携带 {@code schedule} 即为定时调度器；{@code webhook=true} 时服务端生成
 * 回调 token（开放 webhook 触发）；手动触发恒可用（active 前提）。</p>
 *
 * @param name                   调度器名称
 * @param description            描述（可空）
 * @param agentId                指向的 Agent 业务ID
 * @param agentVersion           显式 pin 的 Agent 版本号（可空=创建时解析激活版本并固定）
 * @param environmentId          指向的 Environment 业务ID（可空=回退默认环境）
 * @param environmentVariables   触发会话环境变量 JSON 对象文本（可空透传）
 * @param resources              触发会话挂载资源 JSON 数组文本（可空透传，格式对齐 session resources）
 * @param vaultIds               触发会话保管库业务ID列表（可空）
 * @param initialEvents          首批用户消息事件 JSON 数组文本（可空透传）
 * @param metadata               会话元数据 JSON 对象文本（可空透传）
 * @param schedule               调度配置（可空=仅手动/webhook 触发）
 * @param webhook                是否开放 webhook 触发（true 时服务端生成路径 token）
 */
public record CreateDeploymentCommand(
        String name,
        String description,
        String agentId,
        Integer agentVersion,
        String environmentId,
        String environmentVariables,
        String resources,
        List<String> vaultIds,
        String initialEvents,
        String metadata,
        DeploymentSchedule schedule,
        boolean webhook
) {
}

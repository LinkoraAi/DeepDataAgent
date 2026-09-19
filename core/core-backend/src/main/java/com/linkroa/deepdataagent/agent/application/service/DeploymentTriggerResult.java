package com.linkroa.deepdataagent.agent.application.service;

import org.apache.commons.lang3.StringUtils;

/**
 * 调度器触发结果（手动 / webhook / cron 轮询触发路径的返回载体）。
 *
 * @param deploymentId 触发来源调度器业务 ID
 * @param runId        本次触发产生的运行记录业务 ID（drun_ 前缀）
 * @param sessionId    本次触发新建的会话 ID（每次触发独立新会话，绝不复用）
 */
public record DeploymentTriggerResult(
        String deploymentId,
        String runId,
        String sessionId
) {

    public DeploymentTriggerResult {
        if (StringUtils.isBlank(deploymentId)) {
            throw new IllegalArgumentException("调度器ID不能为空");
        }
        if (StringUtils.isBlank(runId)) {
            throw new IllegalArgumentException("运行记录ID不能为空");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("新建会话ID不能为空");
        }
    }
}

package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 调度器触发响应。
 *
 * @param deployment_id 触发来源调度器业务 ID
 * @param run_id        本次触发产生的运行记录业务 ID（drun_ 前缀）
 * @param session_id    本次触发新建的会话 ID（每次触发独立新会话）
 */
public record DeploymentTriggerResponse(
        String deployment_id,
        String run_id,
        String session_id
) {
}

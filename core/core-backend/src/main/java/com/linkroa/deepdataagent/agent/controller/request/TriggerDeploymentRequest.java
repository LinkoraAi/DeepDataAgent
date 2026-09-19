package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 触发调度器请求。
 *
 * @param input 触发消息（可空，为空时运行时回退默认调度提示）
 */
public record TriggerDeploymentRequest(
        String input
) {
}
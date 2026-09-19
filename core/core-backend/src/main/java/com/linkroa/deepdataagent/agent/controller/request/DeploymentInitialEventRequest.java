package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 调度器首批事件请求项（触发会话时合成的首批用户消息，本期仅支持
 * {@code user_message} 类型，按序合成首个 turn 消息；触发请求显式携带
 * {@code input} 时以 input 优先）。
 *
 * @param type 事件类型（本期仅 {@code user_message}）
 * @param text 消息文本
 */
public record DeploymentInitialEventRequest(
        String type,
        String text
) {
}

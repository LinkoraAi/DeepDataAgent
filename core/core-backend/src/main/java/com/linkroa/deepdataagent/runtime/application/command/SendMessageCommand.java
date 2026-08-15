package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 发送消息命令（触发一轮 agent 执行）。
 *
 * @param sessionId 会话 ID
 * @param message   用户消息全文
 * @param runId     本轮 run ID（可空；为空时由应用服务生成，202 异步场景由接口层预生成返回）
 */
public record SendMessageCommand(
        String sessionId,
        String message,
        String runId
) {

    public SendMessageCommand {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (StringUtils.isBlank(message)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    public SendMessageCommand(String sessionId, String message) {
        this(sessionId, message, null);
    }
}
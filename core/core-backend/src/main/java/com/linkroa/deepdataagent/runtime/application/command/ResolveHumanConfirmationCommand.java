package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 人工确认指令（确认 / 拒绝）命令。
 *
 * @param sessionId 会话 ID
 * @param confirmed true=确认注入结果并恢复执行；false=拒绝并终止当前执行
 */
public record ResolveHumanConfirmationCommand(
        String sessionId,
        boolean confirmed
) {

    public ResolveHumanConfirmationCommand {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
    }
}
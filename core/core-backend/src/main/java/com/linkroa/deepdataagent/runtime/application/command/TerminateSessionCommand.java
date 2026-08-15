package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 终止会话命令。
 *
 * @param sessionId 会话 ID
 */
public record TerminateSessionCommand(String sessionId) {

    public TerminateSessionCommand {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
    }
}
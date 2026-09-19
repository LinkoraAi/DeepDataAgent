package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 人工确认指令（确认 / 拒绝）命令。
 *
 * @param sessionId   会话 ID
 * @param confirmed   true=确认注入结果并恢复执行；false=拒绝该工具调用并按拒绝结果继续推进
 * @param toolUseId   待确认工具调用定位锚点（{@code tool_use_id} = 待确认 {@code agent.tool_use}
 *                    事件的公开 id，可空；非空时必须命中当前等待项批次，不命中视为越界指令 404 拒绝）
 * @param denyMessage 拒绝说明（仅 confirmed=false 时有值，随拒绝结果注入供 Agent 调整后续行为）
 */
public record ResolveHumanConfirmationCommand(
        String sessionId,
        boolean confirmed,
        String toolUseId,
        String denyMessage
) {

    public ResolveHumanConfirmationCommand {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
    }

    /**
     * 便捷构造（不携 {@code tool_use_id} 定位与拒绝说明）。
     */
    public ResolveHumanConfirmationCommand(String sessionId, boolean confirmed) {
        this(sessionId, confirmed, null, null);
    }
}

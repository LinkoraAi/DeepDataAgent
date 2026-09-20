package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * HITL 待确认工具调用明细（领域中性值对象，durable 重建续跑载体）。
 * <p>等待确认的现场完全由事件表承载：确认解析时由应用层从 {@code agent.tool_use}
 * 事件行重建本明细（{@code toolCallId} 取 payload 内 SDK 调用 id），经
 * {@code AgentRunExecutor} 端口传入基础设施层重建 SDK 工具调用块后整批续跑，
 * 正确性不依赖任何进程内暂存对象。</p>
 *
 * @param toolCallId SDK 工具调用 id（事件表 {@code agent.tool_use} payload.id，续流关联键）
 * @param toolName   工具名
 * @param inputJson  工具入参 JSON（可空，null / 空白收敛为空对象串）
 */
public record PendingToolCallSpec(String toolCallId, String toolName, String inputJson) {

    public PendingToolCallSpec {
        if (StringUtils.isBlank(toolCallId)) {
            throw new IllegalArgumentException("待确认工具调用ID不能为空");
        }
        if (StringUtils.isBlank(toolName)) {
            throw new IllegalArgumentException("待确认工具名不能为空");
        }
        if (inputJson == null || inputJson.isBlank()) {
            inputJson = "{}";
        }
    }
}

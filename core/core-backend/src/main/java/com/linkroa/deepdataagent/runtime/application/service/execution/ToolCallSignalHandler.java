package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

import java.util.Map;

/**
 * 工具调用信号策略（变更 R11）：{@code TOOL_CALL_START} / {@code TOOL_CALL_DELTA} / {@code TOOL_CALL_END}。
 * <p>工具域不在 {@code event_deltas[]} 增量协商值域（仅 agent.message / agent.thinking）：START 仅惰性分配
 * 确定性 {@code evt_} ID，DELTA 聚合入参，END 取出入参与事件 ID 后登记 HITL 待确认候选现场并以同一
 * {@code evt_} ID 落库。逐字平移自 {@code handleSignal} 对应 case。</p>
 */
final class ToolCallSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case TOOL_CALL_START -> {
                runState.startToolCall(signal.toolCallId(), signal.toolName());
                runState.ensureToolEventId(signal.toolCallId());
            }
            case TOOL_CALL_DELTA -> runState.appendToolArgs(signal.toolCallId(), signal.text());
            case TOOL_CALL_END -> {
                String toolName = signal.toolName();
                String argsJson = runState.takeToolArgs(signal.toolCallId());
                String eventId = runState.takeToolEventId(signal.toolCallId());
                runState.rememberConfirmCandidate(signal.toolCallId(), toolName, argsJson, eventId);
                ctx.sink().persistAndBroadcast(
                        toolUseEvent(runState, signal.toolCallId(), toolName,
                                ctx.sink().parsePayload(argsJson)), eventId);
                // 登记执行中的调用：tool_use 已落库、结果未到，中断收流时据此补合成错误结果（无悬空 tool_use）
                runState.registerPendingToolUse(signal.toolCallId(), toolName);
            }
            default -> throw new IllegalStateException("ToolCallSignalHandler 收到非工具调用信号: " + signal.type());
        }
    }

    /** 工具调用事件装配：落库 {@code agent.tool_use}。 */
    private static ChatEventFactory.AssembledEvent toolUseEvent(TurnRunState runState, String toolCallId,
                                                                String toolName, Map<String, Object> input) {
        return ChatEventFactory.INSTANCE.toolUse(toolCallId, toolName, input);
    }
}
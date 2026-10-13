package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.shared.security.SecretMasker;

/**
 * 工具结果信号策略（变更 R11）：{@code TOOL_RESULT_TEXT_DELTA} / {@code TOOL_RESULT_END}。
 * <p>DELTA 走 head 实时窗口（未满累积、已满后溢出丢弃，不落库不发布）；END 以 head+tail 截断补发后落库
 * {@code agent.tool_result}，output 先经 {@link SecretMasker#maskSecrets} 形态脱敏（{@code sk-*} /
 * {@code Bearer} 两类），防止明文凭证落库与广播。逐字平移自 {@code handleSignal} 对应 case。</p>
 */
final class ToolResultSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case TOOL_RESULT_TEXT_DELTA -> runState.appendToolResult(signal.toolCallId(), signal.text());
            case TOOL_RESULT_END -> {
                String toolCallId = signal.toolCallId();
                String tail = runState.endToolResult(toolCallId);
                String headPart = SecretMasker.maskSecrets(runState.toolResultHeadText(toolCallId));
                String output = headPart + (tail != null ? SecretMasker.maskSecrets(tail) : "");
                ctx.sink().persistAndBroadcast(toolResultEvent(signal, output,
                        runState.toolResultTruncated(toolCallId)));
                // 配对达成：摘除执行中调用登记，中断收流不再补合成结果
                runState.removePendingToolUse(toolCallId);
            }
            default -> throw new IllegalStateException("ToolResultSignalHandler 收到非工具结果信号: " + signal.type());
        }
    }

    /** 工具结果事件装配：落库 {@code agent.tool_result}。 */
    private static ChatEventFactory.AssembledEvent toolResultEvent(AgentStreamSignal signal, String output,
                                                                   boolean truncated) {
        return ChatEventFactory.INSTANCE.toolResult(signal.toolCallId(), signal.toolName(),
                signal.toolState(), output, truncated);
    }
}

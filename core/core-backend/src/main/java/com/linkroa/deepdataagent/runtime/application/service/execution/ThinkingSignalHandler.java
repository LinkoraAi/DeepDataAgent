package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * 推理文本信号策略（变更 R11）：{@code THINKING_DELTA} / {@code THINKING_END}。
 * <p>thinking 协商仅暴露 {@code event_start} 与 buffered 完整事件，MUST NOT 暴露推理内容（无 delta 帧）；
 * 块结束以帧同一 {@code evt_} ID 落库 {@code agent.thinking}。逐字平移自 {@code handleSignal} 对应 case。</p>
 */
final class ThinkingSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case THINKING_DELTA -> {
                boolean first = runState.markThinkingStreamStarted(signal.blockId());
                String eventId = runState.ensureThinkingEventId(signal.blockId());
                if (first) {
                    ctx.sink().pushFrame(ChatEventFactory.INSTANCE
                            .eventStart(eventId, ChatEventType.AGENT_THINKING), eventId, ChatEventType.AGENT_THINKING);
                    runState.markInFlightStream(eventId, ChatEventType.AGENT_THINKING, signal.blockId(),
                            ctx.currentSequence());
                }
                runState.appendThinking(signal.blockId(), signal.text());
            }
            case THINKING_END -> {
                runState.takeThinking(signal.blockId());
                String eventId = runState.takeThinkingEventId(signal.blockId());
                runState.clearInFlightStream(eventId);
                ctx.sink().persistAndBroadcast(ChatEventFactory.INSTANCE.agentThinking(), eventId);
            }
            default -> throw new IllegalStateException("ThinkingSignalHandler 收到非推理信号: " + signal.type());
        }
    }
}

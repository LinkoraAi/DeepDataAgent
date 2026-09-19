package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * 助手文本信号策略（变更 R11）：{@code TEXT_DELTA} / {@code TEXT_END}。
 * <p>首个 delta 推 {@code event_start} 并登记进行中流，其后片段进聚合缓冲按协商间隔刷写 {@code event_delta}；
 * 块结束排空缓冲、清进行中流、以帧同一 {@code evt_} ID 落库 {@code agent.message}。编排逐字平移自
 * 命令服务 {@code handleSignal} 的对应 case，副作用经 {@link RoundSink} 回调。</p>
 */
final class TextSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case TEXT_DELTA -> {
                boolean first = runState.markTextStreamStarted(signal.blockId());
                String eventId = runState.ensureTextEventId(signal.blockId());
                if (first) {
                    ctx.sink().pushFrame(ChatEventFactory.INSTANCE
                            .eventStart(eventId, ChatEventType.AGENT_MESSAGE), eventId, ChatEventType.AGENT_MESSAGE);
                    runState.markInFlightStream(eventId, ChatEventType.AGENT_MESSAGE, signal.blockId(),
                            ctx.currentSequence());
                }
                runState.appendText(signal.blockId(), signal.text());
                runState.appendPendingDelta(signal.text());
                if (runState.shouldFlushDelta(ctx.deltaFlushIntervalMs())) {
                    ctx.sink().flushTextDelta(runState, eventId);
                }
            }
            case TEXT_END -> {
                String text = runState.takeText(signal.blockId());
                String eventId = runState.takeTextEventId(signal.blockId());
                runState.drainPendingDelta();
                runState.clearInFlightStream(eventId);
                ctx.sink().persistAndBroadcast(ChatEventFactory.INSTANCE.agentMessage(text), eventId);
                runState.markTextMessagePersisted();
            }
            default -> throw new IllegalStateException("TextSignalHandler 收到非文本信号: " + signal.type());
        }
    }
}

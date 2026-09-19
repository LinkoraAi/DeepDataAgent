package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记录型 {@link RoundSink} 测试替身（变更 R11 策略单测）：按调用顺序记录落库 / 推帧 / 刷增量 /
 * 终态 / HITL 挂起 / payload 解析等副作用，令 {@link SignalHandler} 的「信号 → 副作用映射」可被
 * 断言而不依赖真实事务 / 仓储 / 连接层。
 */
final class RecordingRoundSink implements RoundSink {

    /** 落库事件（按调用顺序）。 */
    final List<AssembledEvent> persisted = new ArrayList<>();
    /** 落库时携带的事件 ID（与 {@link #persisted} 对齐，null 表示自动生成）。 */
    final List<String> persistedEventIds = new ArrayList<>();
    /** 推送的流式帧（按调用顺序）。 */
    final List<AssembledEvent> frames = new ArrayList<>();
    /** 流式帧关联的目标事件类型（与 {@link #frames} 对齐）。 */
    final List<ChatEventType> frameTargets = new ArrayList<>();

    int flushTextDeltaCalls;
    int parsePayloadCalls;
    int finalizeNormalCalls;
    int enterWaitingConfirmCalls;

    @Override
    public void persistAndBroadcast(AssembledEvent event) {
        persistAndBroadcast(event, null);
    }

    @Override
    public void persistAndBroadcast(AssembledEvent event, String eventId) {
        persisted.add(event);
        persistedEventIds.add(eventId);
    }

    @Override
    public void pushFrame(AssembledEvent frame, String eventId, ChatEventType targetType) {
        frames.add(frame);
        frameTargets.add(targetType);
    }

    @Override
    public void flushTextDelta(TurnRunState runState, String eventId) {
        flushTextDeltaCalls++;
        // 忠实镜像真实出口：刷写即排空增量缓冲，令聚合节奏（首刷即时、后续按间隔聚合）可被断言
        runState.drainPendingDelta();
    }

    @Override
    public Map<String, Object> parsePayload(String payloadJson) {
        parsePayloadCalls++;
        return Map.of();
    }

    @Override
    public void finalizeNormal() {
        finalizeNormalCalls++;
    }

    @Override
    public void enterWaitingConfirm(AgentStreamSignal signal) {
        enterWaitingConfirmCalls++;
    }
}

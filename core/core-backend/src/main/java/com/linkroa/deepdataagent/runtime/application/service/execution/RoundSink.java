package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

import java.util.Map;

/**
 * 本轮执行副作用出口（变更 R11）：把 {@link SignalHandler} 编排所需的落库 / 广播 / 实时帧 / 终态 /
 * HITL 挂起动作收敛为一个进程内端口，由命令服务在每轮绑定一个实现（捕获本轮 {@code ExecutionContext}
 * 与 {@code TurnControl}），内部原样委托服务既有私有方法。
 * <p>策略经该端口触发副作用而不反向依赖命令服务、不触碰事务 / 仓储 / 连接层，令策略可独立纯单测
 * （以桩 {@code RoundSink} 验证「信号 → 端口调用」映射）。行为与拆分前的巨型 switch 逐字等价。</p>
 * <p><b>可见性</b>：包私有——回接点自 3.2 起全部收口于 {@code execution.TurnExecutionService}
 * 的私有工厂，信号策略与注册表亦同包，本子包外无消费点（5.1 归位复核后由 public 收紧回包私有）。</p>
 */
interface RoundSink {

    /** 落库并广播一条事件（自动生成事件 ID）。 */
    void persistAndBroadcast(AssembledEvent event);

    /** 落库并广播一条事件（复用帧的 {@code evt_} ID，供回放 + 实时重合窗口去重）。 */
    void persistAndBroadcast(AssembledEvent event, String eventId);

    /** 推送流式帧（{@code event_start} / {@code event_delta}）：不落库、不消耗 seq、不入回放。 */
    void pushFrame(AssembledEvent frame, String eventId, ChatEventType targetType);

    /** 排空 delta 聚合缓冲并向协商连接刷写 {@code event_delta} 帧（缓冲为空时静默）。 */
    void flushTextDelta(TurnRunState runState, String eventId);

    /** 解析事件 payload JSON 为 Map（非法收敛为空 Map）。 */
    Map<String, Object> parsePayload(String payloadJson);

    /** SDK 终态（AGENT_END）提前收敛：本轮正常结束终态出口。 */
    void finalizeNormal();

    /** HITL 挂起：进入等待确认态（durable，含批次错配拒绝抛出的既有编排）。 */
    void enterWaitingConfirm(AgentStreamSignal signal);
}

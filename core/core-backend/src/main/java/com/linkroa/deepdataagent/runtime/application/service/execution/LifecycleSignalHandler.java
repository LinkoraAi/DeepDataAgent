package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * 生命周期信号策略（变更 R11）：{@code AGENT_RESULT} / {@code EXCEED_MAX_ITERS} / {@code AGENT_END}。
 * <p>AGENT_RESULT 记录最终文本（供终态兜底落 agent.message）；EXCEED_MAX_ITERS 置迭代上限守卫；
 * AGENT_END 为 SDK 终态（end_turn），除迭代上限与 HITL 等待态外的提前终态——SDK 终态不落库不发布，
 * 终态事件由终态唯一出口合成。逐字平移自 {@code handleSignal} 对应 case。</p>
 */
final class LifecycleSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case AGENT_RESULT -> runState.setFinalResultText(signal.resultText());
            case EXCEED_MAX_ITERS -> runState.markExceedMaxIters();
            case AGENT_END -> {
                if (!runState.exceededMaxIters() && !runState.confirmationPending()) {
                    ctx.sink().finalizeNormal();
                }
            }
            default -> throw new IllegalStateException("LifecycleSignalHandler 收到非生命周期信号: " + signal.type());
        }
    }
}

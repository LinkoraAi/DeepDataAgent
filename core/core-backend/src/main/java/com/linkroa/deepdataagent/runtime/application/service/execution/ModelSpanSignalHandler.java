package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * 模型调用 span 信号策略（变更 R11）：{@code MODEL_CALL_START} / {@code MODEL_CALL_END}。
 * <p>模型调用边界由 {@code span.model_request_start} / {@code span.model_request_end} 事件承载（落库审计）；
 * END 下沉 usage（token 计量 + 模型标识），成本读时按 token × 价格派生，模型名由 END 兜底记忆。
 * 逐字平移自 {@code handleSignal} 对应 case。</p>
 */
final class ModelSpanSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case MODEL_CALL_START -> {
                runState.markModelCallStart();
                ctx.sink().persistAndBroadcast(
                        ChatEventFactory.INSTANCE.modelRequestStart(runState.lastModelName()),
                        runState.ensureModelStartEventId());
            }
            case MODEL_CALL_END -> {
                runState.rememberModelName(signal.modelName());
                ctx.sink().persistAndBroadcast(ChatEventFactory.INSTANCE.modelRequestEnd(
                        runState.takeModelStartEventId(), false,
                        signal.inputTokens(), signal.outputTokens(), signal.modelName()));
            }
            default -> throw new IllegalStateException("ModelSpanSignalHandler 收到非模型 span 信号: " + signal.type());
        }
    }
}

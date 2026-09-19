package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HITL 人机确认信号策略（变更 R11）：{@code HUMAN_CONFIRM_REQUIRED} / {@code HUMAN_CONFIRM_RESULT}。
 * <p>挂起委托 hitl 子域编排（{@link RoundSink#enterWaitingConfirm}：processing → waiting_confirmation +
 * 批次明细事件 durable，含 D19「批次错配即拒绝」的抛出），本策略不重复其事务 / 状态机细节；
 * 确认结果已注入并恢复执行（状态事件在续跑侧广播），此处仅日志确认。逐字平移自 {@code handleSignal}
 * 对应 case。</p>
 */
final class HitlSignalHandler implements SignalHandler {

    private static final Logger log = LoggerFactory.getLogger(HitlSignalHandler.class);

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        switch (signal.type()) {
            case HUMAN_CONFIRM_REQUIRED -> ctx.sink().enterWaitingConfirm(signal);
            case HUMAN_CONFIRM_RESULT ->
                    log.info("人工确认结果已注入: sessionId={}, replyId={}", ctx.sessionId(), signal.replyId());
            default -> throw new IllegalStateException("HitlSignalHandler 收到非 HITL 信号: " + signal.type());
        }
    }
}

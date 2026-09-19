package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * 单个流信号的处理上下文（变更 R11）：一条待处理信号 + 本轮执行现场的只读视图 + 落库广播出口。
 * <p>由命令服务在 {@code handleSignal} 骨架内按信号构造、经 {@link SignalHandlerRegistry} 分发给
 * 对应 {@link SignalHandler}。上下文本身不承载业务逻辑，仅把策略所需的可变运行态
 * （{@link #runState()}）、连接层协商参数（{@link #sessionContext()}）与副作用出口（{@link #sink()}）
 * 一并显式传入，避免策略反向依赖命令服务或读取 ThreadLocal。</p>
 * <p><b>可见性</b>：包私有——分发入口与全部策略实现均在 {@code execution} 子包内
 * （5.1 归位复核后由 public 收紧回包私有），本子包外无消费点。</p>
 *
 * @param signal         待处理信号
 * @param sessionContext 会话级聚合（连接层 / 序列号基准 / 会话镜像）
 * @param runState       本轮运行态门面（五组件可变面）
 * @param sink           本轮落库 / 广播 / 终态 / HITL 挂起副作用出口（绑定本轮执行现场）
 */
record SignalContext(AgentStreamSignal signal, AgentSessionContext sessionContext,
                            TurnRunState runState, RoundSink sink) {

    /** 会话 ID（策略日志用）。 */
    String sessionId() {
        return sessionContext.sessionId();
    }

    /** 当前已分配序列号（进行中流 baseSeq 基准，不消耗 seq）。 */
    long currentSequence() {
        return sessionContext.currentSequence();
    }

    /** 连接层协商的 delta 刷写间隔（毫秒）。 */
    long deltaFlushIntervalMs() {
        return sessionContext.connection().deltaFlushIntervalMs();
    }
}

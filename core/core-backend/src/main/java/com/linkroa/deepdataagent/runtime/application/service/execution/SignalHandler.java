package com.linkroa.deepdataagent.runtime.application.service.execution;

/**
 * 单个流信号的处理策略（变更 R11）。
 * <p>每个实现按信号类型分组消费 {@link AgentStreamSignal}，把「状态累积 → 源码事件装配 → 落库广播 /
 * 实时帧推送 / 终态触发」编排从命令服务的巨型 switch 中拆出，令「新信号 = 新策略、分发器零修改」。
 * 策略无状态：可变面全部落在 {@link SignalContext#runState()} 的 {@code TurnRunState} 五组件上，
 * 落库 / 广播 / 终态 / HITL 挂起等进程内编排副作用经 {@link RoundSink} 回调命令服务既有方法承担。</p>
 */
interface SignalHandler {

    /**
     * 处理一条信号。
     *
     * @param ctx 本轮信号处理上下文（信号 + 执行现场 + 落库广播出口）
     */
    void handle(SignalContext ctx);
}

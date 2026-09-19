package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import reactor.core.scheduler.Scheduler;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单轮执行上下文（显式依赖面，替代原 {@code runAgent} / {@code runResumeStream} 两条同构执行体的重复参数）。
 * <p>由命令服务在 {@code withTurn} 内、调用 {@link RoundExecutionTemplate#run} 前一次性装配，
 * 显式携带本轮执行所需的全部<b>数据</b>（会话镜像 / 会话级聚合 / 控制面 / 运行态 / 线程归属）、
 * <b>端口</b>（装配工厂 / 装配解析器 / 阻塞调度器 / 续约调度器 / 协调层租约）与
 * <b>编排回调</b>（信号消费 / 收流 / 异常收流 / 取消谓词 / 中断出口 / 失败出口 / Agent 释放出口）。</p>
 * <p>本类不使用 ThreadLocal：所有可变面经字段显式传递（design D1）。新一轮与 HITL 续跑的唯一差异
 * 是流供给函数（{@code streamEvents} / {@code resumeConfirmation}），其余骨架完全复用
 * {@link RoundExecutionTemplate}。编排回调由执行链服务（decompose-command-facade 3.2 起为
 * {@code execution.TurnExecutionService}）绑定到其既有私有方法
 * （{@code handleSignal} / {@code onStreamComplete} / {@code onStreamError} / {@code isCancelRequested}
 * / {@code finalizeInterrupted} / {@code finalizeAsFailure}），令模板不持有业务编排职责。</p>
 * <p><b>可见性</b>：public（永久）——本类型是执行链续跑入口 {@code TurnExecutionService#newRoundContext}
 * 的返回类型与 {@code runRound} 的入参类型，跨子包由 {@code hitl.HumanConfirmationService} 持有续跑现场，
 * 故不可收紧为包私有（5.1 归位复核结论）；本包外的执行族类型（{@code RoundSink} / {@code SignalContext}
 * / {@code RoundExecutionTemplate} / {@code SignalHandlerRegistry}）均已收紧回包私有。</p>
 *
 * @param sessionContext       会话级聚合（逻辑线程组，跨 turn 常驻）
 * @param session              会话镜像（身份信息 / 终态判定）
 * @param turn                 本轮执行控制面（完成信号与中断注册）
 * @param runState             本轮运行态门面（beginRound 产出）
 * @param sessionThreadId      主线程归属 ID（启动 / 领取事务内解析一次，随现场传递；null = 归属未知降级）
 * @param agentFactory         Agent 装配工厂端口（{@code build}）
 * @param assembler            装配规格解析（{@code runtimeAgentAssemblyService::assemble}）
 * @param blockingScheduler    事件流 {@code publishOn} 阻塞调度器
 * @param leaseRenewalScheduler turn 租约定时续约调度端口（承载形态经
 *                              {@code app.coordination.lease-renewal} 切换，见 {@code LeaseRenewalScheduler}）
 * @param leaseService         协调层租约服务（续约 / fail-closed 判定）
 * @param signalHandler        单信号消费回调（绑定 {@code handleSignal}）
 * @param onStreamComplete     流正常收流回调（绑定 {@code onStreamComplete}）
 * @param onStreamError        流异常收流回调（绑定 {@code onStreamError}）
 * @param cancelRequested      在途取消两源谓词（绑定 {@code isCancelRequested}）
 * @param onAwaitInterrupted   阻塞等待被外部中断出口（记日志 + {@code finalizeInterrupted}）
 * @param onRunFailure         执行体抛出运行时异常出口（记日志 + {@code finalizeAsFailure}，入参含 setup 失败标记）
 * @param onAgentCloseFailure  Agent 句柄释放异常出口（记日志）
 * @param roundLabel           本轮日志语义标签（"turn" / "续跑轮"）
 */
public record RoundContext(
        AgentSessionContext sessionContext,
        AgentSession session,
        TurnControl turn,
        TurnRunState runState,
        String sessionThreadId,
        AgentFactoryPort agentFactory,
        Function<AgentSession, AgentAssemblySpec> assembler,
        Scheduler blockingScheduler,
        LeaseRenewalScheduler leaseRenewalScheduler,
        CoordinationLeaseService leaseService,
        Consumer<AgentStreamSignal> signalHandler,
        Runnable onStreamComplete,
        Consumer<Throwable> onStreamError,
        BooleanSupplier cancelRequested,
        Runnable onAwaitInterrupted,
        BiConsumer<Throwable, Boolean> onRunFailure,
        Consumer<RuntimeException> onAgentCloseFailure,
        String roundLabel) {

    /** 会话 ID（本轮日志与中断句柄同源键）。 */
    String sessionId() {
        return session.sessionId();
    }

    /** 用户 ID（中断句柄与执行下发运行时的会话身份同源键）。 */
    String userId() {
        return session.userId();
    }
}

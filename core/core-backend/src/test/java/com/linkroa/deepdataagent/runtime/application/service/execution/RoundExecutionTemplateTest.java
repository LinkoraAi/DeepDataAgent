package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RoundExecutionTemplate} 执行骨架单测（变更 R10，design D5）：
 * 用假 {@link BuiltAgent} + 冷流对两路径做行为快照，覆盖续约启停、四出口
 * （正常 / 出错 / 外部中断 / leaseLost 静默中止）、装配失败与流供给失败分支。
 * <p>模板对信号语义不介入（编排回调由本用例提供并记录），故本用例锁定「骨架顺序与出口」，
 * 与命令服务 115 个用例端到端复用的终态编排互补。</p>
 */
class RoundExecutionTemplateTest {

    private static final String SESSION_ID = "sess_x";
    private static final String USER_ID = "u1";

    private RoundExecutionTemplate template;
    private AgentFactoryPort agentFactory;
    private BuiltAgent agent;
    private AgentSession session;
    private LeaseRenewalScheduler leaseRenewalScheduler;
    private LeaseRenewalHandle renewalHandle;
    private CoordinationLeaseService leaseService;
    private final Scheduler blockingScheduler = Schedulers.immediate();

    // 回调记录面
    private final List<AgentStreamSignalType> handled = new ArrayList<>();
    private final AtomicBoolean streamCompleted = new AtomicBoolean();
    private final AtomicReference<Throwable> streamError = new AtomicReference<>();
    private final AtomicBoolean awaitInterrupted = new AtomicBoolean();
    private final AtomicBoolean runFailureCalled = new AtomicBoolean();
    private final AtomicReference<Boolean> runFailureSetup = new AtomicReference<>();
    private final AtomicReference<Throwable> runFailureEx = new AtomicReference<>();
    private final AtomicBoolean agentCloseFailureCalled = new AtomicBoolean();
    private final BooleanSupplier cancelRequested = () -> false;

    @BeforeEach
    void setUp() {
        template = new RoundExecutionTemplate();
        agentFactory = mock(AgentFactoryPort.class);
        agent = mock(BuiltAgent.class);
        session = mock(AgentSession.class);
        when(session.sessionId()).thenReturn(SESSION_ID);
        when(session.userId()).thenReturn(USER_ID);
        when(agentFactory.build(any())).thenReturn(agent);
        leaseRenewalScheduler = mock(LeaseRenewalScheduler.class);
        renewalHandle = mock(LeaseRenewalHandle.class);
        leaseService = mock(CoordinationLeaseService.class);
        when(leaseRenewalScheduler.schedule(any(), any())).thenReturn(renewalHandle);
    }

    /** 组装带真实运行态 / 控制面与记录型回调的执行上下文。 */
    private RoundContext newContext(TurnRunState runState, TurnControl turn, String roundLabel,
                                    Consumer<AgentStreamSignal> signalHandler) {
        return new RoundContext(
                mock(AgentSessionContext.class),
                session,
                turn,
                runState,
                null,
                agentFactory,
                s -> emptyAssemblySpec(),
                blockingScheduler,
                leaseRenewalScheduler,
                leaseService,
                signalHandler,
                () -> {
                    streamCompleted.set(true);
                    turn.finish();
                },
                error -> {
                    streamError.set(error);
                    turn.finish();
                },
                cancelRequested,
                () -> awaitInterrupted.set(true),
                (ex, setupFailure) -> {
                    runFailureCalled.set(true);
                    runFailureEx.set(ex);
                    runFailureSetup.set(setupFailure);
                },
                ex -> agentCloseFailureCalled.set(true),
                roundLabel);
    }

    /** 最小可装配规格（无挂载凭证 / 无工具策略）：骨架用例聚焦出口与续约行为，不介入登记面。 */
    private static AgentAssemblySpec emptyAssemblySpec() {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent_x", "测试 Agent"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(1)
                .withSandbox(new AgentAssemblySpec.Sandbox("ubuntu:22.04", null, null))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), Map.of(), List.of()))
                .build();
    }

    /** 记录信号并回放（AGENT_END 顺带触发定向中断以验证句柄同源）。 */
    private Consumer<AgentStreamSignal> recording(TurnControl turn) {
        return signal -> {
            handled.add(signal.type());
            if (signal.type() == AgentStreamSignalType.AGENT_END) {
                turn.cancel();
            }
        };
    }

    private static AgentStreamSignal sig(AgentStreamSignalType type) {
        return AgentStreamSignal.of(type, null, null);
    }

    @Test
    void should_runFixedSkeletonAndCloseAgent_when_run_given_newRoundCompletingStream() {
        // given
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));
        Function<BuiltAgent, Flux<AgentStreamSignal>> flux =
                a -> Flux.just(sig(AgentStreamSignalType.TEXT_DELTA), sig(AgentStreamSignalType.AGENT_END));

        // when
        template.run(ctx, flux);

        // then 装配 → 事件按序消费 → 中断句柄同源触发 → 正常收流 → 续约启停 → 句柄释放
        assertEquals(List.of(AgentStreamSignalType.TEXT_DELTA, AgentStreamSignalType.AGENT_END), handled);
        assertTrue(streamCompleted.get());
        assertNull(streamError.get());
        assertFalse(runFailureCalled.get());
        verify(agentFactory, times(1)).build(any());
        verify(agent, times(1)).interrupt(USER_ID, SESSION_ID);
        verify(leaseRenewalScheduler, times(1)).schedule(any(), any());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
    }

    @Test
    void should_shareSkeletonAndDifferOnlyByFluxSupply_when_run_given_resumeRound() {
        // given 续跑轮：相同骨架、不同流供给（模型调用起始信号 + AGENT_END）
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "续跑轮", recording(turn));
        Function<BuiltAgent, Flux<AgentStreamSignal>> flux =
                a -> Flux.just(sig(AgentStreamSignalType.MODEL_CALL_START), sig(AgentStreamSignalType.AGENT_END));

        // when
        template.run(ctx, flux);

        // then 装配 / 释放 / 续约启停与新一轮一致，仅事件序列反映续跑流供给
        assertEquals(List.of(AgentStreamSignalType.MODEL_CALL_START, AgentStreamSignalType.AGENT_END), handled);
        assertTrue(streamCompleted.get());
        assertFalse(runFailureCalled.get());
        verify(agentFactory, times(1)).build(any());
        verify(agent, times(1)).interrupt(USER_ID, SESSION_ID);
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
    }

    @Test
    void should_invokeRunFailureWithSetupFlag_when_run_given_buildThrows() {
        // given 装配失败（流尚未订阅）
        RuntimeException boom = new RuntimeException("build failed");
        when(agentFactory.build(any())).thenThrow(boom);
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));

        // when
        template.run(ctx, a -> Flux.just(sig(AgentStreamSignalType.AGENT_END)));

        // then 失败出口携带 setup=true，订阅 / 释放均不发生（agent 未产出）；
        // 续约在装配前已启动，并在出口统一停止（不泄漏虚续约）
        assertTrue(runFailureCalled.get());
        assertSame(boom, runFailureEx.get());
        assertTrue(runFailureSetup.get());
        assertTrue(handled.isEmpty());
        verify(leaseRenewalScheduler, times(1)).schedule(any(), any());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, never()).close();
        assertFalse(streamCompleted.get());
    }

    @Test
    void should_invokeRunFailureWithoutSetupFlag_when_run_given_fluxSupplierThrows() {
        // given 已装配、流供给抛错（runStream 之前）
        RuntimeException boom = new RuntimeException("supply failed");
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));

        // when
        template.run(ctx, a -> {
            throw boom;
        });

        // then 失败出口 setup=false，已产出的 agent 仍被释放；续约在装配前已启动并在出口停止
        assertTrue(runFailureCalled.get());
        assertSame(boom, runFailureEx.get());
        assertFalse(runFailureSetup.get());
        verify(leaseRenewalScheduler, times(1)).schedule(any(), any());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
    }

    @Test
    void should_startLeaseRenewalBeforeAssembly_when_run_given_anyRound() {
        // given（装配含 MCP 同步建连 + 工具枚举，可达数分钟；续约若在装配之后才起，
        // 该窗口无人续约、10min TTL 可能先到期而被他实例接管）
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));
        InOrder inOrder = inOrder(leaseRenewalScheduler, agentFactory);

        // when
        template.run(ctx, a -> Flux.just(sig(AgentStreamSignalType.AGENT_END)));

        // then（续约启动严格先于装配）
        inOrder.verify(leaseRenewalScheduler).schedule(any(), any());
        inOrder.verify(agentFactory).build(any());
    }

    @Test
    void should_routeFluxErrorToStreamError_when_run_given_fluxErrors() {
        // given 冷流以异常终结
        RuntimeException boom = new RuntimeException("stream boom");
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));

        // when
        template.run(ctx, a -> Flux.error(boom));

        // then onError 出口收到异常，onComplete 不触发；续约停、句柄释放
        assertSame(boom, streamError.get());
        assertFalse(streamCompleted.get());
        assertTrue(handled.isEmpty());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
        assertFalse(runFailureCalled.get());
    }

    @Test
    void should_completeQuietlyAndRelease_when_run_given_leaseLostDuringRound() throws Exception {
        // given 首轮后流永挂起（不发 onComplete），外部置 leaseLost → fail-closed 静默中止收轮
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            template.run(ctx, a -> Flux.concat(
                    Flux.just(sig(AgentStreamSignalType.TEXT_DELTA)), Flux.never()));
            done.countDown();
        });
        worker.start();
        try {
            // 持续置位直至中止句柄被建立 / 触发（先置位后注册双向补偿，令断言与竞态无关）
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && done.getCount() > 0) {
                runState.markLeaseLost();
                if (done.await(20, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            // then 收轮完成：流未正常收流（无 onComplete），句柄仍释放、续约停止
            assertTrue(done.await(2_000, TimeUnit.MILLISECONDS), "fail-closed 中止未收轮（awaitFinish 悬挂）");
        } finally {
            worker.interrupt();
        }
        assertTrue(runState.leaseLost());
        assertFalse(streamCompleted.get());
        assertFalse(runFailureCalled.get());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
    }

    @Test
    void should_runAwaitInterruptedExit_when_run_given_threadInterruptedDuringAwait() throws Exception {
        // given 冷流永挂起，外部中断令 awaitFinish 抛 InterruptedException
        TurnRunState runState = new TurnRunState();
        TurnControl turn = new TurnControl();
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            template.run(ctx, a -> Flux.<AgentStreamSignal>never()
                    .doOnSubscribe(s -> subscribed.countDown()));
            done.countDown();
        });
        worker.start();
        // when 确认进入订阅后中断等待线程
        assertTrue(subscribed.await(2_000, TimeUnit.MILLISECONDS), "冷流未订阅");
        worker.interrupt();

        // then 中断出口被执行、完成信号放行、句柄释放、续约停止
        assertTrue(done.await(2_000, TimeUnit.MILLISECONDS), "中断后 awaitFinish 未放行");
        assertTrue(awaitInterrupted.get());
        assertFalse(streamCompleted.get());
        assertFalse(runFailureCalled.get());
        verify(renewalHandle, times(1)).cancel();
        verify(agent, times(1)).close();
    }

    // ==================== 续约失败二分类（move-coordination-leases-to-redis D5①） ====================

    /** 跑完一轮即时收流，取回续约任务体（不真实调度，供用例按「周期」手工触发）。 */
    private Runnable captureRenewalTask(TurnRunState runState, TurnControl turn) {
        RoundContext ctx = newContext(runState, turn, "turn", recording(turn));
        template.run(ctx, a -> Flux.just(sig(AgentStreamSignalType.AGENT_END)));
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(leaseRenewalScheduler, times(1))
                .schedule(taskCaptor.capture(), any());
        return taskCaptor.getValue();
    }

    @Test
    void should_markLeaseLostImmediately_when_leaseRenewal_given_storeReturnsConfirmedLoss() {
        // given（存储确证失权：续约返回 false——租约过期被回收或已被他实例接管）
        TurnRunState runState = new TurnRunState();
        when(leaseService.renewTurnLease(SESSION_ID)).thenReturn(false);

        // when
        captureRenewalTask(runState, new TurnControl()).run();

        // then（立即 fail-closed，不做任何重试容忍）
        assertTrue(runState.leaseLost());
        verify(leaseService, times(1)).renewTurnLease(SESSION_ID);
    }

    @Test
    void should_retryOnceInSameCycleAndSurvive_when_leaseRenewal_given_transientFaultThenSuccess() {
        // given（首轮连接抖动：抛异常 → 当周期重试 1 次 → 成功）
        TurnRunState runState = new TurnRunState();
        when(leaseService.renewTurnLease(SESSION_ID))
                .thenThrow(new IllegalStateException("redis connection lost"))
                .thenReturn(true);

        // when
        Runnable task = captureRenewalTask(runState, new TurnControl());
        task.run();

        // then（当周期重试一次即恢复，故障计数归零，绝不误杀在途轮次）
        verify(leaseService, times(2)).renewTurnLease(SESSION_ID);
        assertFalse(runState.leaseLost());
        // 下一周期正常续约 → 仍不失权
        task.run();
        assertFalse(runState.leaseLost());
    }

    @Test
    void should_markLeaseLostAfterTwoFaultCycles_when_leaseRenewal_given_persistentStoreFault() {
        // given（存储持续不可用：每次续约都抛异常）
        TurnRunState runState = new TurnRunState();
        when(leaseService.renewTurnLease(SESSION_ID)).thenThrow(new IllegalStateException("redis down"));

        // when 第一个故障周期（含当周期重试共 2 次调用）
        Runnable task = captureRenewalTask(runState, new TurnControl());
        task.run();

        // then 一个周期仍异常仅容忍，不 fail-closed
        assertFalse(runState.leaseLost());
        verify(leaseService, times(2)).renewTurnLease(SESSION_ID);

        // when 第二个连续故障周期
        task.run();

        // then（连续 2 周期取不到确证成功 → fail-closed 中止在途执行）
        assertTrue(runState.leaseLost());
        verify(leaseService, times(4)).renewTurnLease(SESSION_ID);
    }

    @Test
    void should_skipRenewal_when_leaseRenewal_given_leaseAlreadyLost() {
        // given（已 fail-closed：后续周期不再打存储，避免毒续约）
        TurnRunState runState = new TurnRunState();
        runState.markLeaseLost();

        // when
        Runnable task = captureRenewalTask(runState, new TurnControl());
        task.run();

        // then
        verify(leaseService, never()).renewTurnLease(any());
    }
}

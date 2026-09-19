package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StartupRecoveryLifecycle} 启动恢复单测（协调层租约承载：按<b>本实例</b>租约键恢复 +
 * 相位选择性复位孤儿执行；审查修复 F13：多实例互不互踢）。
 * <p>恢复编排两步（move-coordination-leases-to-redis）：① 释放本实例 owner 索引中的 turn 租约
 * （不读会话、不改状态）；② 对 {@code findActiveExecutionSessionIds()} 候选中不存在任何存活实例
 * 有效租约的会话以 {@link Transition#ABANDON_ORPHAN_EXECUTION} CAS 复位 {@code idle / idle}
 * （{@code awaiting_confirmation} 天然不在候选内，durable HITL 等待跨重启不复位）。</p>
 * <p>过期租约回收已随端口切换退役（move-coordination-leases-to-redis D3-1），故本测试不再
 * 断言 reaper 调用。</p>
 * <p>形态迭代（2026-09）：{@code ApplicationRunner} → 低 phase {@code SmartLifecycle}，
 * 恢复先于 HTTP 端口绑定完成，封死「恢复窗口内本实例新轮被自家恢复击杀」竞态；
 * 触发入口由 {@code run(arguments)} 变为 {@code start()}，恢复业务断言逐条保留。</p>
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryLifecycleTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private CoordinationLeaseService coordinationLeaseService;

    private StartupRecoveryLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new StartupRecoveryLifecycle();
    }

    /** 通过字段注入装配 lifecycle 的依赖（@Resource 字段注入，测试中不依赖 Spring 容器）。 */
    private void wireLifecycle(boolean recoveryEnabled) {
        // 运行时全局配置为纯数据载体，直注真实配置对象（迭代裁决 2026-09-13：不再经端口包装）
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        runtimeProperties.setStartupRecoveryEnabled(recoveryEnabled);
        ReflectionTestUtils.setField(lifecycle, "runtimeProperties", runtimeProperties);
        ReflectionTestUtils.setField(lifecycle, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(lifecycle, "coordinationLeaseService", coordinationLeaseService);
    }

    /** 活跃执行会话夹具（对外 running + 内部相位 running，孤儿复位候选形状）。 */
    private AgentSession activeExecutionSession() {
        return AgentSession.create("u-1", "agent-a", "1", "{}", null)
                .withStatus(AgentSessionStatus.RUNNING)
                .withPhase(TurnPhase.RUNNING);
    }

    @Test
    void should_recoverByOwnLeaseKey_when_start_given_ownActiveTurnLease() {
        // given（本实例残留有效 turn 租约：租约需释放，活跃执行会话经兜底复位）
        wireLifecycle(true);
        String sessionId = activeExecutionSession().sessionId();
        when(coordinationLeaseService.listOwnActiveTurnKeys())
                .thenReturn(List.of(CoordLeaseType.TURN.keyOf(sessionId)));
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of(sessionId));

        // when
        lifecycle.start();

        // then（按租约键恢复：释放本实例残留租约 + 孤儿执行相位选择性复位 idle）
        verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository).transition(sessionId, Transition.ABANDON_ORPHAN_EXECUTION);
    }

    @Test
    void should_notTouchOtherInstanceLeases_when_start_given_foreignLeaseExists() {
        // given（F13：其他存活实例持有的租约不枚举——端口按 owner 过滤后返回空）
        wireLifecycle(true);
        when(coordinationLeaseService.listOwnActiveTurnKeys()).thenReturn(List.of());
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of());

        // when
        lifecycle.start();

        // then（不释放任何租约、不复位任何会话）
        verify(coordinationLeaseService, never()).releaseTurnLease(anyString());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.ABANDON_ORPHAN_EXECUTION));
    }

    @Test
    void should_resetLeftoverOrphanExecution_when_start_given_noLeaseButActiveExecutionSession() {
        // given（无租约的活跃执行会话：进程崩溃残留，兜底 CAS 复位）
        wireLifecycle(true);
        when(coordinationLeaseService.listOwnActiveTurnKeys()).thenReturn(List.of());
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of("s-2"));

        // when
        lifecycle.start();

        // then（s-2 无任何有效租约 → 兜底复位）
        verify(sessionRepository).transition("s-2", Transition.ABANDON_ORPHAN_EXECUTION);
    }

    @Test
    void should_skipActiveExecutionSession_when_start_given_sessionHeldByOtherInstance() {
        // given（F13：活跃执行会话仍被存活实例租约锁定 → 兜底不得复位）
        wireLifecycle(true);
        when(coordinationLeaseService.listOwnActiveTurnKeys()).thenReturn(List.of());
        when(coordinationLeaseService.hasActiveTurnLease("s-live")).thenReturn(true);
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of("s-live"));

        // when
        lifecycle.start();

        // then（他实例在跑的会话不触碰）
        verify(sessionRepository, never()).transition("s-live", Transition.ABANDON_ORPHAN_EXECUTION);
    }

    @Test
    void should_preserveWaitingConfirmation_when_start_given_leaseSessionWaitingConfirmation() {
        // given（durable HITL：awaiting_confirmation 不在活跃执行候选中，重启不复位不作废）
        wireLifecycle(true);
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null)
                .withStatus(AgentSessionStatus.RUNNING)
                .withPhase(TurnPhase.AWAITING_CONFIRMATION);
        String sessionId = session.sessionId();
        when(coordinationLeaseService.listOwnActiveTurnKeys())
                .thenReturn(List.of(CoordLeaseType.TURN.keyOf(sessionId)));
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of());

        // when
        lifecycle.start();

        // then：仅回收崩溃残留租约，会话状态保持等待（确认 / 拒绝可在重启后经账本重建续跑）
        verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.ABANDON_ORPHAN_EXECUTION));
    }

    @Test
    void should_resetToIdle_when_start_given_leaseSessionCancelling() {
        // given（租约残留且会话处于取消中相位 CANCELING：崩溃收敛后同样 CAS 复位 idle）
        wireLifecycle(true);
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null)
                .withStatus(AgentSessionStatus.RUNNING)
                .withPhase(TurnPhase.CANCELLING);
        String sessionId = session.sessionId();
        when(coordinationLeaseService.listOwnActiveTurnKeys())
                .thenReturn(List.of(CoordLeaseType.TURN.keyOf(sessionId)));
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of(sessionId));

        // when
        lifecycle.start();

        // then
        verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository).transition(sessionId, Transition.ABANDON_ORPHAN_EXECUTION);
    }

    @Test
    void should_notResetTerminalSession_when_start_given_leaseSessionAlreadyTerminated() {
        // given（租约残留但会话已终态：仅释放租约，不动状态）
        wireLifecycle(true);
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null)
                .withStatus(AgentSessionStatus.TERMINATED);
        String sessionId = session.sessionId();
        when(coordinationLeaseService.listOwnActiveTurnKeys())
                .thenReturn(List.of(CoordLeaseType.TURN.keyOf(sessionId)));
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of());

        // when
        lifecycle.start();

        // then
        verify(coordinationLeaseService).releaseTurnLease(sessionId);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.ABANDON_ORPHAN_EXECUTION));
    }

    @Test
    void should_releaseAndResetOnce_when_start_given_sessionLeasedAndListedActive() {
        // given（同一会话既持本实例租约又出现在活跃执行列表：释放 / 复位各恰好一次）
        wireLifecycle(true);
        String sessionId = activeExecutionSession().sessionId();
        when(coordinationLeaseService.listOwnActiveTurnKeys())
                .thenReturn(List.of(CoordLeaseType.TURN.keyOf(sessionId)));
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of(sessionId));

        // when
        lifecycle.start();

        // then（无重复释放 / 无重复复位）
        verify(coordinationLeaseService, times(1)).releaseTurnLease(sessionId);
        verify(sessionRepository, times(1)).transition(sessionId, Transition.ABANDON_ORPHAN_EXECUTION);
    }

    @Test
    void should_doNothing_when_start_given_noLeasesNoActiveExecutionSessions() {
        // given
        wireLifecycle(true);
        when(coordinationLeaseService.listOwnActiveTurnKeys()).thenReturn(List.of());
        when(sessionRepository.findActiveExecutionSessionIds()).thenReturn(List.of());

        // when
        lifecycle.start();

        // then
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.ABANDON_ORPHAN_EXECUTION));
        verify(coordinationLeaseService, never()).releaseTurnLease(anyString());
    }

    @Test
    void should_skip_when_start_given_recoveryDisabled() {
        // given（关闭恢复开关：不枚举租约、不查询活跃执行会话）
        wireLifecycle(false);

        // when
        lifecycle.start();

        // then
        verify(coordinationLeaseService, never()).listOwnActiveTurnKeys();
        verify(sessionRepository, never()).findActiveExecutionSessionIds();
    }

    @Test
    void should_startBeforeWebServerBinding_when_getPhase_given_recoveryMustGateTraffic() {
        // given（web 服务器生命周期 phase：WebServerStartStopLifecycle = Integer.MAX_VALUE - 1，
        // 端口受理于此之后；调度器 @Scheduled 组 phase = Integer.MAX_VALUE）

        // when
        int phase = lifecycle.getPhase();

        // then（恢复必须先于两者完成，否则启动窗口内新轮会被自家恢复误杀）
        assertTrue(phase < Integer.MAX_VALUE - 1, "phase 必须低于 web 服务器绑定 phase");
    }

    @Test
    void should_markRunningAfterRecovery_when_start_given_recoveryCompleted() {
        // given（恢复成功完成后生命周期标记置 true；禁用开关同样视为完成——跳过即成功语义）
        wireLifecycle(false);
        assertFalse(lifecycle.isRunning(), "start 前生命周期未运行");

        // when
        lifecycle.start();

        // then
        assertTrue(lifecycle.isRunning(), "恢复完成后生命周期应报告运行中");
    }
}
package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoundGuard} 单测（原 {@code AgentRunStateTest} 的租约丢失三原语、挂起守卫、迭代上限断言原样平移）。
 * <p>覆盖 task 2.4 三原语：先置位后注册补偿、{@code markLeaseLost} 幂等、null 句柄安全。</p>
 */
class RoundGuardTest {

    @Test
    void should_markExceedMaxIters_when_markExceedMaxIters_given_default() {
        // given
        RoundGuard guard = new RoundGuard();

        // when & then（迭代上限标记仅作 stop_reason 派生输入，不产出终态）
        assertFalse(guard.exceededMaxIters());
        guard.markExceedMaxIters();
        assertTrue(guard.exceededMaxIters());
    }

    @Test
    void should_flipConfirmationPending_when_markConfirmationPending_given_initialRound() {
        // given
        RoundGuard guard = new RoundGuard();

        // when & then：初始未挂起，标记后为真（AGENT_END / onComplete 终态守卫依据）
        assertFalse(guard.confirmationPending());
        guard.markConfirmationPending();
        assertTrue(guard.confirmationPending());
    }

    @Test
    void should_flipInterrupted_when_markInterrupted_given_initialRound() {
        // given
        RoundGuard guard = new RoundGuard();

        // when & then
        assertFalse(guard.interrupted());
        guard.markInterrupted();
        assertTrue(guard.interrupted());
    }

    @Test
    void should_triggerAbortOnAttach_when_attachLeaseLostAbort_given_markedBeforeAttach() {
        // given（续约失败早于订阅建立：先置位、句柄尚未注册）
        RoundGuard guard = new RoundGuard();
        AtomicInteger abortRuns = new AtomicInteger();
        assertTrue(guard.markLeaseLost());
        assertTrue(guard.leaseLost());

        // when（订阅建立后补注册中止句柄）
        guard.attachLeaseLostAbort(abortRuns::incrementAndGet);

        // then（补偿触发：标记先于注册时句柄仍立即执行一次，中止信号不丢失）
        assertEquals(1, abortRuns.get());
    }

    @Test
    void should_triggerAbortOnceAndSwallow_when_markLeaseLost_given_attachedAbortAndSecondMark() {
        // given（订阅先建立、句柄已注册；句柄含异常动作模拟 dispose 抛出）
        RoundGuard guard = new RoundGuard();
        AtomicInteger abortRuns = new AtomicInteger();
        guard.attachLeaseLostAbort(() -> {
            abortRuns.incrementAndGet();
            throw new IllegalStateException("模拟 dispose 异常（须被吞掉，不影响幂等）");
        });

        // when（首次置位触发句柄；异常不向上传播）
        boolean first = guard.markLeaseLost();
        boolean second = guard.markLeaseLost();

        // then（首次置位返回 true 且句柄执行一次；重复置位 CAS 失败返回 false、句柄不再执行）
        assertTrue(first);
        assertFalse(second);
        assertTrue(guard.leaseLost());
        assertEquals(1, abortRuns.get());
    }

    @Test
    void should_ignoreNullAbort_when_attachLeaseLostAbort_given_null() {
        // given
        RoundGuard guard = new RoundGuard();

        // when（null 句柄注册为空操作，不抛异常）
        guard.attachLeaseLostAbort(null);

        // then（置位 / 判定原语照常工作，无 NPE）
        assertFalse(guard.leaseLost());
        assertTrue(guard.markLeaseLost());
        assertTrue(guard.leaseLost());
    }

    @Test
    void should_grantFinalizationOnce_when_tryAcquireFinalization_given_repeatCalls() {
        // given
        RoundGuard guard = new RoundGuard();

        // when & then（CAS 领取资格：首次 true、重复 false，纯 CAS 不含清理副作用）
        assertTrue(guard.tryAcquireFinalization());
        assertFalse(guard.tryAcquireFinalization());
    }
}

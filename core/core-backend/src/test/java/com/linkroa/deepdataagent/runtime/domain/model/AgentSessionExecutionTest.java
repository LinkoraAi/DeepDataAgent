package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSessionExecution} 执行层单测：串行守卫、重复提交拒绝、中断句柄触发与取消幂等。
 */
class AgentSessionExecutionTest {

    @Test
    void should_rejectConcurrentSubmit_when_submit_given_activeExecution() throws InterruptedException {
        // given（首个任务在后执行阻塞占住执行槽）
        AgentSessionExecution execution = new AgentSessionExecution();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> execution.submit(ctx -> {
            entered.countDown();
            await(release);
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(execution.isRunning());

        // when & then（第二个提交被串行守卫拒绝）
        assertThrows(IllegalStateException.class, () -> execution.submit(ctx -> {
        }));
        release.countDown();
        first.join();
    }

    @Test
    void should_clearSlotAfterTask_when_submit_given_taskCompletes() {
        // given
        AgentSessionExecution execution = new AgentSessionExecution();
        AtomicBoolean runningDuring = new AtomicBoolean(false);

        // when（同步提交：任务执行期 isRunning 为 true，结束后清除）
        execution.submit(ctx -> runningDuring.set(execution.isRunning()));

        // then
        assertTrue(runningDuring.get());
        assertFalse(execution.isRunning());
    }

    @Test
    void should_triggerInterrupter_when_cancel_given_activeExecution() throws InterruptedException {
        // given（活跃执行已注册中断句柄）
        AgentSessionExecution execution = new AgentSessionExecution();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread first = Thread.ofVirtual().start(() -> execution.submit(ctx -> {
            ctx.activate(() -> interrupted.set(true));
            entered.countDown();
            await(release);
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // when
        execution.cancel();

        // then（中断句柄被触发，槽位被清除）
        assertTrue(interrupted.get());
        assertFalse(execution.isRunning());
        release.countDown();
        first.join();
    }

    @Test
    void should_doNothing_when_cancel_given_noActiveExecution() {
        // given（无活跃执行）
        AgentSessionExecution execution = new AgentSessionExecution();

        // when & then（取消为空操作、幂等，不抛异常）
        execution.cancel();
        execution.cancel();
        assertFalse(execution.isRunning());
    }

    @Test
    void should_triggerInterrupterImmediately_when_activate_given_cancelledBeforeActivate() throws InterruptedException {
        // given（cancel 先于 activate 到达，处理注册竞态防止泄漏）
        AgentSessionExecution execution = new AgentSessionExecution();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread first = Thread.ofVirtual().start(() -> execution.submit(ctx -> {
            entered.countDown();
            await(release);
            ctx.activate(() -> interrupted.set(true));
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // when（先 cancel 再 activate）
        execution.cancel();
        release.countDown();
        first.join();

        // then（activate 感知 cancel 已到，立即触发一次）
        assertTrue(interrupted.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
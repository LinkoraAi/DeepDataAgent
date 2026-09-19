package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnControl} 单轮执行控制面单测：activate / cancel 竞态补偿、中断句柄触发与取消幂等、
 * finish 解除 awaitFinish 阻塞（承接原执行槽类测试的竞态用例 + 完成放行用例）。
 */
class TurnControlTest {

    @Test
    void should_trigger_interrupt_immediately_when_activate_given_cancel_arrived_first() {
        // given（cancel 先于 activate 到达：此刻标志已置位但尚无句柄）
        TurnControl turn = new TurnControl();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        turn.cancel();

        // when（中断句柄迟到登记，竞态补偿须立即触发一次）
        turn.activate(() -> interrupted.set(true));

        // then（activate 感知 cancel 已到，注册即触发，防在飞模型流失去停止句柄）
        assertTrue(interrupted.get(), "cancel 先到时 activate 须立即触发中断句柄");
    }

    @Test
    void should_trigger_interrupt_once_when_cancel_given_interrupter_registered() {
        // given（中断句柄先登记）
        TurnControl turn = new TurnControl();
        AtomicInteger fireCount = new AtomicInteger();
        turn.activate(fireCount::incrementAndGet);

        // when
        turn.cancel();

        // then（已登记句柄被触发一次）
        assertEquals(1, fireCount.get(), "cancel 须触发已登记的中断句柄一次");
    }

    @Test
    void should_noop_when_cancel_given_no_interrupter_registered() {
        // given（无任何中断句柄登记）
        TurnControl turn = new TurnControl();

        // when & then（取消为空操作、幂等，不抛异常）
        assertDoesNotThrow(() -> {
            turn.cancel();
            turn.cancel();
        });
    }

    @Test
    void should_release_waiting_thread_when_finish_given_awaiting() throws InterruptedException {
        // given（虚拟线程阻塞等待本轮终局）
        TurnControl turn = new TurnControl();
        CountDownLatch released = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                turn.awaitFinish();
                released.countDown();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        // when（终态路径放行完成信号）
        turn.finish();

        // then（阻塞线程被解除）
        assertTrue(released.await(2, TimeUnit.SECONDS), "finish 须解除 awaitFinish 阻塞");
        waiter.join();
    }
}

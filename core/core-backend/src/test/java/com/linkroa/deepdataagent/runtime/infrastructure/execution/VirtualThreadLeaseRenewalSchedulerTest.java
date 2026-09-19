package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VirtualThreadLeaseRenewalScheduler} 单测：容量预案承载（每轮一个 daemon 虚拟线程
 * sleep 循环）的周期语义、停止语义与异常存续（move-coordination-leases-to-redis D5③）。
 *
 * <p>周期取几十毫秒量级以稳住用例耗时；断言只锁定「是否按周期重复」「停止后不再执行」
 * 「任务异常不影响循环」三类行为，不做精确节拍断言（虚拟线程 sleep 受载体线程调度影响）。</p>
 */
class VirtualThreadLeaseRenewalSchedulerTest {

    private static final Duration FAST_INTERVAL = Duration.ofMillis(30);

    private final VirtualThreadLeaseRenewalScheduler scheduler = new VirtualThreadLeaseRenewalScheduler();

    @Test
    void should_runTaskRepeatedlyOnDaemonVirtualThread_when_schedule_given_interval() throws Exception {
        // given
        CountDownLatch twice = new CountDownLatch(2);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> daemonFlag = new AtomicReference<>(false);

        // when
        LeaseRenewalHandle handle = scheduler.schedule(() -> {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            daemonFlag.set(Thread.currentThread().isDaemon());
            runs.incrementAndGet();
            twice.countDown();
        }, FAST_INTERVAL);

        // then（按周期重复执行，且承载为 daemon 虚拟线程——thread-per-round 不占平台线程）
        boolean executed = twice.await(2_000, TimeUnit.MILLISECONDS);
        handle.cancel();
        assertTrue(executed, "续约循环未按周期重复执行，实际次数=" + runs.get());
        assertTrue(runs.get() >= 2);
        assertTrue(threadName.get() != null && threadName.get().startsWith("lease-renew-vt-"),
                "续约线程名应带 lease-renew-vt- 前缀，实际=" + threadName.get());
        assertTrue(daemonFlag.get(), "虚拟续约线程必须是 daemon（不得阻塞 JVM 退出）");
    }

    @Test
    void should_stopScheduling_when_cancel_given_runningLoop() throws Exception {
        // given
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        LeaseRenewalHandle handle = scheduler.schedule(() -> {
            runs.incrementAndGet();
            started.countDown();
        }, FAST_INTERVAL);
        assertTrue(started.await(2_000, TimeUnit.MILLISECONDS), "续约任务从未执行");

        // when
        handle.cancel();
        int afterCancel = runs.get();
        Thread.sleep(FAST_INTERVAL.toMillis() * 5);

        // then（停止后不再产生新周期）
        assertFalse(runs.get() > afterCancel, "cancel 后续约仍在执行");
    }

    @Test
    void should_keepLoopAlive_when_taskThrowsRuntimeException_given_failingRenewal() throws Exception {
        // given（任务体兜底：抛异常不得终结续约循环——池形态下会静默丢调度，本形态必须存续）
        CountDownLatch attempts = new CountDownLatch(3);
        AtomicBoolean throwing = new AtomicBoolean(true);

        // when
        LeaseRenewalHandle handle = scheduler.schedule(() -> {
            attempts.countDown();
            if (throwing.get()) {
                throw new IllegalStateException("renewal blew up");
            }
        }, FAST_INTERVAL);

        // then（连续三次周期均被触发，异常被循环吞留）
        boolean reached = attempts.await(3_000, TimeUnit.MILLISECONDS);
        throwing.set(false);
        handle.cancel();
        assertTrue(reached, "任务异常后续约循环被终结，剩余计数=" + attempts.getCount());
    }

    @Test
    void should_beIdempotent_when_cancel_given_calledTwiceBeforeFirstRun() {
        // given（尚未到首个周期即停止：重复 cancel 不抛异常，任务永不执行）
        AtomicBoolean executed = new AtomicBoolean(false);
        LeaseRenewalHandle handle = scheduler.schedule(() -> executed.set(true), Duration.ofSeconds(30));

        // when // then
        assertDoesNotThrow(() -> {
            handle.cancel();
            handle.cancel();
        });
        assertFalse(executed.get(), "cancel 后不应执行续约任务");
    }
}

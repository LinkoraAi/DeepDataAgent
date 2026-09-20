package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 续约调度容量预案承载：每轮一个 daemon 虚拟线程 sleep 循环（move-coordination-leases-to-redis D5③）。
 *
 * <p>与 {@link ScheduledPoolLeaseRenewalScheduler} 互斥二选一（{@code app.coordination.lease-renewal
 * =virtual-thread} 才装配）。动机：平台续约池线程数固定，并发轮次上量且 Redis 变慢
 * （命令超时 2s）时续约任务会在池队列排队，长 turn 可能连续错过周期而逼近 TTL；
 * thread-per-round 的虚拟线程让「同时在运行的续约循环数」不再受池大小约束，
 * {@code sleep} 期间让出载体线程（Java 21 Loom M:N），数千轮次的内存代价仅为数千个挂起的虚拟线程。</p>
 *
 * <p><b>语义对齐</b>：首次执行在 {@code interval} 之后（与 {@code scheduleAtFixedRate} 一致）；
 * 周期为固定 sleep（不做上次耗时补偿，续约幂等且 TTL/3 留有余量，无需精确对齐）；
 * 任务体抛异常时循环保留至下一周期（池形态下未捕获异常会静默取消后续调度——本形态更稳）。</p>
 */
@Component
@ConditionalOnProperty(name = "app.coordination.lease-renewal", havingValue = "virtual-thread")
public class VirtualThreadLeaseRenewalScheduler implements LeaseRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadLeaseRenewalScheduler.class);

    @Override
    public LeaseRenewalHandle schedule(Runnable task, Duration interval) {
        long periodMillis = Math.max(1L, interval.toMillis());
        AtomicBoolean stopped = new AtomicBoolean(false);
        // 虚拟线程恒为 daemon：JVM 退出不被其阻塞，停机由 @PreDestroy 主动 cancel 收口
        Thread worker = Thread.ofVirtual()
                .name("lease-renew-vt-", 0)
                .start(() -> renewLoop(task, periodMillis, stopped));
        return () -> {
            if (stopped.compareAndSet(false, true)) {
                // 打断沉睡中的 sleep，令退出即时生效；已进入的单次续约不强行中断
                worker.interrupt();
            }
        };
    }

    /** 续约循环：先睡后做（首轮延迟一个周期），停止标志在睡眠前后各检一次。 */
    private static void renewLoop(Runnable task, long periodMillis, AtomicBoolean stopped) {
        while (!stopped.get()) {
            try {
                Thread.sleep(periodMillis);
            } catch (InterruptedException ex) {
                // cancel 打断或线程池关闭：按正常退出处理，MUST NOT 继续续约
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped.get()) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException ex) {
                // 任务体约定自消化异常，此处为兜底：保留循环等下一周期，绝不让异常终止续约线程
                log.error("续约任务未预期异常（本周期跳过，循环保留）: thread={}",
                        Thread.currentThread().getName(), ex);
            }
        }
    }
}

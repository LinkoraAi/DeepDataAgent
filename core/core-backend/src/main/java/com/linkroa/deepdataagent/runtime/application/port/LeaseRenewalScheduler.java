package com.linkroa.deepdataagent.runtime.application.port;

import java.time.Duration;

/**
 * turn 租约定时续约调度端口（move-coordination-leases-to-redis D5③ 高并发容量预案）。
 *
 * <p>轮次执行体以 {@code TTL/3} 周期续约持有中的 turn 租约（确证失权即 fail-closed）。续约的
 * <b>承载形态</b>是可切换的技术决策，与业务规则无关，故执行模板只依赖本端口，不直接持有
 * {@code ScheduledExecutorService}：</p>
 * <ul>
 *   <li>{@code scheduler}（默认）——平台线程池 {@code scheduleAtFixedRate}：任务体极短（一次
 *       owner-scoped 续约命令），小池即可；并发轮次极高时任务会在池队列排队，TTL/3 周期留了
 *       两次错过的余量（{@code LEASE_FAULT_TOLERANT_CYCLES}）；</li>
 *   <li>{@code virtual-thread}——<b>每轮一个 daemon 虚拟线程 sleep 循环</b>：轮次数量不再受池
 *       线程数约束（thread-per-round，阻塞 {@code sleep} 时让出载体线程），用于续约池成为
 *       瓶颈时的容量兜底。</li>
 * </ul>
 *
 * <p>两实现见 {@code runtime.infrastructure.execution}，经
 * {@code app.coordination.lease-renewal} 二选一装配；无论哪种承载，<b>续约语义（周期、二分类、
 * fail-closed 门槛）完全由调用方任务体决定，端口不增不改</b>。</p>
 */
public interface LeaseRenewalScheduler {

    /**
     * 以固定周期启动续约任务（首次执行在 {@code interval} 之后，与 {@code scheduleAtFixedRate} 一致）。
     *
     * @param task     续约任务体（调用方 MUST 内部消化异常——两种承载均不保证异常后的调度存续）
     * @param interval 续约周期（{@code turn 租约 TTL / 3}）
     * @return 可停止句柄（轮次全部出口统一 {@code cancel}，杜绝毒续约）
     */
    LeaseRenewalHandle schedule(Runnable task, Duration interval);
}

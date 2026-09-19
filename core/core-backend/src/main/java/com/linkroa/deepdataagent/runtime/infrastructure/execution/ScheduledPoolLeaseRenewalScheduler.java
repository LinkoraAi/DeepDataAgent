package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 续约调度默认承载：平台线程定时池（move-coordination-leases-to-redis D5③）。
 *
 * <p>复用 {@code AgentExecutorConfig} 的 {@code leaseRenewalExecutor} 池（4 个 daemon 平台线程），
 * 任务体仅一次 owner-scoped 续约命令（毫秒级），小池即可覆盖常规并发。</p>
 *
 * <p>装配条件：{@code app.coordination.lease-renewal=scheduler} 或缺省（{@code matchIfMissing}）——
 * 与 {@link VirtualThreadLeaseRenewalScheduler} 互斥二选一，容器中恒只存在一个
 * {@link LeaseRenewalScheduler} bean。</p>
 */
@Component
@ConditionalOnProperty(name = "app.coordination.lease-renewal", havingValue = "scheduler", matchIfMissing = true)
public class ScheduledPoolLeaseRenewalScheduler implements LeaseRenewalScheduler {

    /** 平台线程续约池（任务极短，池满即排队；TTL/3 周期容忍两次错过）。 */
    @Resource(name = "leaseRenewalExecutor")
    private ScheduledExecutorService leaseRenewalExecutor;

    @Override
    public LeaseRenewalHandle schedule(Runnable task, Duration interval) {
        long periodMillis = Math.max(1L, interval.toMillis());
        ScheduledFuture<?> future = leaseRenewalExecutor
                .scheduleAtFixedRate(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        // cancel(false)：不强行打断执行中的单次续约（与拆分前语义逐字一致）
        return () -> future.cancel(false);
    }
}

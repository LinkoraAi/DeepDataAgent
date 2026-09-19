package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduledPoolLeaseRenewalScheduler} 单测：续约调度默认承载（平台定时池）的
 * 调度参数与停止句柄语义（move-coordination-leases-to-redis D5③）。
 */
@ExtendWith(MockitoExtension.class)
class ScheduledPoolLeaseRenewalSchedulerTest {

    @Mock private ScheduledExecutorService executor;
    @Mock private ScheduledFuture<?> future;

    private ScheduledPoolLeaseRenewalScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledPoolLeaseRenewalScheduler();
        ReflectionTestUtils.setField(scheduler, "leaseRenewalExecutor", executor);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void should_scheduleFixedRateWithEqualInitialDelayAndPeriod_when_schedule_given_interval() {
        // given
        Runnable task = () -> { };

        // when
        scheduler.schedule(task, Duration.ofMillis(200_000));

        // then（首轮延迟一个周期、其后固定周期——与拆分前 scheduleAtFixedRate 参数逐字一致）
        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> periodCaptor = ArgumentCaptor.forClass(Long.class);
        verify(executor).scheduleAtFixedRate(eq(task), delayCaptor.capture(), periodCaptor.capture(),
                eq(TimeUnit.MILLISECONDS));
        assertEquals(200_000L, delayCaptor.getValue());
        assertEquals(200_000L, periodCaptor.getValue());
    }

    @Test
    void should_floorPeriodToOneMillis_when_schedule_given_nonPositiveInterval() {
        // given（防御：零 / 负周期不得直传 JDK 触发参数非法）

        // when
        scheduler.schedule(() -> { }, Duration.ZERO);

        // then
        verify(executor).scheduleAtFixedRate(any(Runnable.class), eq(1L), eq(1L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void should_cancelWithoutInterrupt_when_cancel_given_handleFromSchedule() {
        // given // when
        LeaseRenewalHandle handle = scheduler.schedule(() -> { }, Duration.ofSeconds(1));
        handle.cancel();

        // then（cancel(false)：执行中的单次续约不强行打断）
        verify(future).cancel(false);
        verify(future, never()).cancel(true);
    }

    @Test
    void should_beIdempotent_when_cancel_given_calledTwice() {
        // given // when（重复停止不抛异常）
        LeaseRenewalHandle handle = scheduler.schedule(() -> { }, Duration.ofSeconds(1));

        // then
        assertDoesNotThrow(() -> {
            handle.cancel();
            handle.cancel();
        });
        verify(future, times(2)).cancel(anyBoolean());
    }
}

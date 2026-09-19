package com.linkroa.deepdataagent.agent.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeploymentScheduler} 单测（D14 调度基线轮询入口）：
 * tick 以 Asia/Shanghai 当前时刻与配置批量调用到期触发、本轮触发数大于 0 正常收敛、
 * 单轮异常仅吞掉记日志（下一轮自然重试，轮询幂等）。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentSchedulerTest {

    @Mock private DeploymentApplicationService deploymentApplicationService;

    private DeploymentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DeploymentScheduler();
        ReflectionTestUtils.setField(scheduler, "deploymentApplicationService", deploymentApplicationService);
        ReflectionTestUtils.setField(scheduler, "batchSize", 7);
    }

    @Test
    void should_pollShanghaiNowWithConfiguredBatch_when_tick_given_serviceReady() {
        // given（本轮无到期候选）
        when(deploymentApplicationService.triggerDueDeployments(any(), anyInt())).thenReturn(0);

        // when
        scheduler.tick();

        // then（起算时刻为上海时区当前瞬刻，批量取配置值）
        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(deploymentApplicationService).triggerDueDeployments(nowCaptor.capture(), limitCaptor.capture());
        assertEquals(7, limitCaptor.getValue());
        assertEquals(ZoneOffset.ofHours(8), nowCaptor.getValue().getOffset());
        OffsetDateTime now = nowCaptor.getValue();
        assertFalse(now.isBefore(OffsetDateTime.now().minusMinutes(1)), "轮询起算时刻不应早于当前时刻 1 分钟");
        assertFalse(now.isAfter(OffsetDateTime.now().plusMinutes(1)), "轮询起算时刻不应晚于当前时刻 1 分钟");
    }

    @Test
    void should_completeQuietly_when_tick_given_deploymentsFired() {
        // given（本轮领取触发 2 个到期调度器）
        when(deploymentApplicationService.triggerDueDeployments(any(), anyInt())).thenReturn(2);

        // when // then（正常收敛不抛出）
        assertDoesNotThrow(() -> scheduler.tick());
        verify(deploymentApplicationService).triggerDueDeployments(any(), anyInt());
    }

    @Test
    void should_swallowRoundFailure_when_tick_given_serviceThrows() {
        // given（单轮触发异常：仅记日志，不向上冒泡打断调度线程）
        when(deploymentApplicationService.triggerDueDeployments(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        // when // then（异常被吞，tick 正常返回等待下一轮重试）
        assertDoesNotThrow(() -> scheduler.tick());
    }

    @Test
    void should_pollEveryTick_when_tick_given_repeatedInvocations() {
        // given（连续多轮轮询：每轮独立发起一次到期触发）
        when(deploymentApplicationService.triggerDueDeployments(any(), anyInt())).thenReturn(0);

        // when
        scheduler.tick();
        scheduler.tick();

        // then
        verify(deploymentApplicationService, times(2)).triggerDueDeployments(any(), anyInt());
    }
}

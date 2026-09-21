package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.rag.application.task.CleanupInFlightRegistry;
import com.linkroa.deepdataagent.rag.application.task.InFlightTaskConverger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DeletionCleanupTaskExecutor} 删除清退虚拟线程执行器单测。
 * <p>覆盖：任务提交即异步执行、同一在飞键重复提交幂等返回 false、不同键并行推进、
 * 并发配额耗尽时后到任务阻塞至前序释放、任务体抛异常仍释放在飞登记与配额、
 * 停机 / 未初始化拒收新提交（抛 {@code IllegalStateException}）、参数防御、
 * 停机宽限内被中断任务的在飞登记释放。</p>
 *
 * <p>停机链另覆盖：首个动作清空本实例注册表键、宽限期满后按在飞清点逐一致失败收敛
 * （文档置 DELETE_FAILED、整库置 SHUTDOWN 留痕）、无残留时不产生收敛动作、
 * 执行器未初始化时仍先清注册表键。</p>
 *
 * <p>在飞登记器以 {@code @Spy} 承载真实实现（被测执行器与登记器共同构成本组件的去重账本，
 * 无法用 Mock 表达其状态语义），停机收敛器按规范以 {@code @Mock} 仅校验交互；
 * 其余以 latch 显式编排异步时序并设等待上限防 flaky。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DeletionCleanupTaskExecutorTest {

    /** 知识库在飞键 */
    private static final String KB_KEY = CleanupInFlightRegistry.kbKey(71L);

    /** 另一知识库在飞键 */
    private static final String OTHER_KB_KEY = CleanupInFlightRegistry.kbKey(72L);

    /** 文档在飞键（与知识库键数值相同，验证键空间隔离） */
    private static final String DOC_KEY = CleanupInFlightRegistry.docKey(71L);

    /** 异步编排等待上限（毫秒），防死等导致测试挂住 */
    private static final long ASYNC_TIMEOUT_MILLIS = 5000L;

    /** 判定「未发生」的短等待窗口（毫秒） */
    private static final long NEGATIVE_WAIT_MILLIS = 200L;

    /** 停机收敛文档清退的置失败留痕（须与被测执行器常量口径一致） */
    private static final String SHUTDOWN_DOC_CLEANUP_TRACE = "[DELETE-FAILED] step=shutdown";

    /** 停机收敛整库清退的置失败留痕（须与被测执行器常量口径一致） */
    private static final String SHUTDOWN_KB_CLEANUP_TRACE =
            "[KB-CLEANUP] step=SHUTDOWN: 服务停机中断，请重新执行删除";

    /** 真实行为的在飞去重登记器（执行器构造依赖，同时作为停机收敛的在飞清点来源） */
    @Spy
    private CleanupInFlightRegistry inFlightRegistry = new CleanupInFlightRegistry();

    /** 在飞任务收敛器（执行器构造依赖，停机收敛交互以 Mock 校验） */
    @Mock
    private InFlightTaskConverger inFlightTaskConverger;

    /** 被测执行器 */
    @InjectMocks
    private DeletionCleanupTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void should_executeTaskAndReleaseInFlight_when_submit_given_validKeyAndTask() throws InterruptedException {
        // given：配额与默认一致，任务体仅放行一个闩
        CountDownLatch started = new CountDownLatch(1);

        // when
        boolean accepted = executor.submit(KB_KEY, started::countDown);

        // then：受理成功、任务在虚拟线程内执行、收尾释放在飞登记
        assertTrue(accepted);
        assertTrue(started.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "任务体未在虚拟线程内执行");
        assertTrue(awaitInFlightReleased(KB_KEY), "任务收尾后在飞登记未释放");
    }

    @Test
    void should_returnFalseAndSkipTask_when_submit_given_keyAlreadyInFlight() throws InterruptedException {
        // given：模拟另一任务已持有该库的在飞登记
        assertTrue(inFlightRegistry.tryRegister(KB_KEY));
        CountDownLatch started = new CountDownLatch(1);

        // when
        boolean accepted = executor.submit(KB_KEY, started::countDown);

        // then：幂等跳过——不执行任务、不触碰他人登记
        assertFalse(accepted);
        assertFalse(started.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS), "在飞去重命中后仍执行了任务体");
        assertTrue(inFlightRegistry.isInFlight(KB_KEY), "重复提交误释放了原持有者的在飞登记");
    }

    @Test
    void should_runBothTasks_when_submit_given_differentKeys() throws InterruptedException {
        // given：两个任务在同一闸门上并行等待（同时抵达闸门才能放行）
        CountDownLatch arrived = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);

        // when
        boolean firstAccepted = executor.submit(KB_KEY, () -> awaitQuietly(arrived, gate));
        boolean secondAccepted = executor.submit(OTHER_KB_KEY, () -> awaitQuietly(arrived, gate));
        gate.countDown();

        // then：不同键互不去重，且真并行（两任务同时抵达闸门）
        assertTrue(firstAccepted);
        assertTrue(secondAccepted);
        assertTrue(arrived.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "两个不同键任务未并行推进");
        awaitInFlightReleased(KB_KEY);
        awaitInFlightReleased(OTHER_KB_KEY);
    }

    @Test
    void should_runDocKeyAlongsideKbKey_when_submit_given_sameIdDifferentResourceType()
            throws InterruptedException {
        // given：知识库与文档主键数值相同（71），键空间以 kb: / doc: 前缀隔离
        CountDownLatch kbArrived = new CountDownLatch(1);
        CountDownLatch docArrived = new CountDownLatch(1);

        // when
        boolean kbAccepted = executor.submit(KB_KEY, kbArrived::countDown);
        boolean docAccepted = executor.submit(DOC_KEY, docArrived::countDown);

        // then：同数值不同资源类型不被误去重
        assertTrue(kbAccepted);
        assertTrue(docAccepted);
        assertTrue(kbArrived.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue(docArrived.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    }

    @Test
    void should_releaseQuotaAndInFlight_when_submit_given_taskThrowsUnexpected() throws InterruptedException {
        // given：配额 1，首个任务体意外抛出（提交方漏 catch 时的兜底路径）
        ReflectionTestUtils.setField(executor, "maxConcurrent", 1);
        executor.initialize();
        AtomicInteger executed = new AtomicInteger();

        // when
        assertTrue(executor.submit(KB_KEY, () -> {
            executed.incrementAndGet();
            throw new IllegalStateException("清退步骤异常");
        }));
        assertTrue(awaitInFlightReleased(KB_KEY), "任务抛异常后在飞登记未释放");

        // then：配额同样归还——同键可再次受理并执行
        CountDownLatch nextStarted = new CountDownLatch(1);
        assertTrue(executor.submit(KB_KEY, nextStarted::countDown), "异常任务未归还并发配额");
        assertTrue(nextStarted.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(1, executed.get());
    }

    @Test
    void should_blockThirdTaskUntilQuotaReleased_when_submit_given_maxConcurrentTwo()
            throws InterruptedException {
        // given：配额 2，前两个任务占满配额后阻塞在闸门上
        ReflectionTestUtils.setField(executor, "maxConcurrent", 2);
        executor.initialize();
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);

        // when
        assertTrue(executor.submit(KB_KEY, () -> awaitQuietly(occupied, gate)));
        assertTrue(executor.submit(OTHER_KB_KEY, () -> awaitQuietly(occupied, gate)));
        assertTrue(occupied.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "前两个任务未占满配额");
        assertTrue(executor.submit(CleanupInFlightRegistry.kbKey(73L), () -> {
            thirdStarted.countDown();
            awaitGate(gate);
        }));

        // then：配额耗尽时第三个任务排队等待而非丢弃
        assertFalse(thirdStarted.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS), "配额耗尽时第三个任务仍获准执行");
        gate.countDown();
        assertTrue(thirdStarted.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "配额释放后第三个任务未被推进");
    }

    @Test
    void should_throwIllegalState_when_submit_given_shutdownExecutor() {
        // given
        executor.shutdown();

        // when // then：停机拒收，交由调用方留痕、资源停留 DELETING
        assertThrows(IllegalStateException.class, () -> executor.submit(KB_KEY, () -> {
        }));
        assertFalse(inFlightRegistry.isInFlight(KB_KEY), "被拒收的提交不应留下在飞登记");
    }

    @Test
    void should_throwIllegalState_when_submit_given_executorNotInitialized() {
        // given：未走 @PostConstruct 的执行器（尚未初始化）
        DeletionCleanupTaskExecutor notInitialized =
                new DeletionCleanupTaskExecutor(inFlightRegistry, inFlightTaskConverger);

        // when // then
        assertThrows(IllegalStateException.class, () -> notInitialized.submit(KB_KEY, () -> {
        }));
    }

    @Test
    void should_throwIllegalArgument_when_submit_given_blankInFlightKey() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> executor.submit("  ", () -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> executor.submit(null, () -> {
        }));
    }

    @Test
    void should_throwIllegalArgument_when_submit_given_nullTask() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> executor.submit(KB_KEY, null));
        assertFalse(inFlightRegistry.isInFlight(KB_KEY));
    }

    @Test
    void should_releaseInFlightOfInterruptedTask_when_shutdown_given_zeroGraceAndTaskWaitingQuota()
            throws InterruptedException {
        // given：配额 1（首任务占住配额并等待闸门），次任务在配额上排队；宽限 0 即不等待直接中断
        ReflectionTestUtils.setField(executor, "maxConcurrent", 1);
        ReflectionTestUtils.setField(executor, "shutdownGraceSeconds", 0L);
        executor.initialize();
        CountDownLatch gate = new CountDownLatch(1);
        assertTrue(executor.submit(KB_KEY, () -> awaitGate(gate)));
        assertTrue(executor.submit(OTHER_KB_KEY, () -> awaitGate(gate)));

        // when：停机（宽限 0）——在飞任务被中断，零补偿
        assertDoesNotThrow(executor::shutdown);
        gate.countDown();

        // then：被中断任务一定释放在飞登记（停留 DELETING 交启动恢复重触发）
        assertTrue(awaitInFlightReleased(KB_KEY), "首任务收尾未释放在飞登记");
        assertTrue(awaitInFlightReleased(OTHER_KB_KEY), "等待配额被中断的任务未释放在飞登记");
    }

    @Test
    void should_clearRegistryFirst_when_shutdown_given_inFlightTasks() {
        // given：停机宽限 0（不等待），且在飞登记器内确有本实例残留
        ReflectionTestUtils.setField(executor, "shutdownGraceSeconds", 0L);
        assertTrue(inFlightRegistry.tryRegister(KB_KEY));

        // when
        executor.shutdown();

        // then：停机链首个动作即清空本实例注册表键（消除滚动发布期间新旧进程并存误判）
        verify(inFlightTaskConverger, times(1)).clearInstanceRegistryQuietly();
    }

    @Test
    void should_convergeInFlightResidue_when_shutdown_given_documentAndKbInFlight() {
        // given：停机宽限 0，在飞登记器内存有一个文档清退任务与一个整库清退任务
        ReflectionTestUtils.setField(executor, "shutdownGraceSeconds", 0L);
        assertTrue(inFlightRegistry.tryRegister(CleanupInFlightRegistry.docKey(1L)));
        assertTrue(inFlightRegistry.tryRegister(CleanupInFlightRegistry.kbKey(2L)));

        // when
        executor.shutdown();

        // then：宽限期满后按在飞清点逐一致失败收敛（文档置 DELETE_FAILED、整库提示重新删除）
        verify(inFlightTaskConverger, times(1))
                .converge(InFlightTaskType.DOC_CLEANUP, 1L, SHUTDOWN_DOC_CLEANUP_TRACE);
        verify(inFlightTaskConverger, times(1))
                .converge(InFlightTaskType.KB_CLEANUP, 2L, SHUTDOWN_KB_CLEANUP_TRACE);
    }

    @Test
    void should_notConverge_when_shutdown_given_noResidue() {
        // given：停机宽限 0，在飞登记器为空（无残留）
        ReflectionTestUtils.setField(executor, "shutdownGraceSeconds", 0L);

        // when
        executor.shutdown();

        // then：无残留则不产生任何收敛动作（零写操作）
        verify(inFlightTaskConverger, never()).converge(any(), any(), any());
    }

    @Test
    void should_stillClearRegistry_when_shutdown_given_executorNotInitialized() {
        // given：未走 @PostConstruct 的执行器（executor 尚未初始化）
        DeletionCleanupTaskExecutor notInitialized =
                new DeletionCleanupTaskExecutor(inFlightRegistry, inFlightTaskConverger);

        // when
        notInitialized.shutdown();

        // then：清空注册表键先于执行器判空，未初始化亦须先消除本实例键
        verify(inFlightTaskConverger, times(1)).clearInstanceRegistryQuietly();
    }

    /**
     * 等待某在飞键被释放（任务收尾为异步，轮询直至超时）。
     *
     * @param inFlightKey 在飞键
     * @return {@code true} 表示在超时上限内已释放
     * @throws InterruptedException 轮询等待被中断
     */
    private boolean awaitInFlightReleased(String inFlightKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!inFlightRegistry.isInFlight(inFlightKey)) {
                return true;
            }
            Thread.sleep(20L);
        }
        return !inFlightRegistry.isInFlight(inFlightKey);
    }

    /**
     * 任务体编排：登记「已抵达」后等待闸门放行（闸门超时仅中断退出，不影响断言）。
     *
     * @param arrived 抵达计数闩
     * @param gate    放行闸门
     */
    private void awaitQuietly(CountDownLatch arrived, CountDownLatch gate) {
        arrived.countDown();
        awaitGate(gate);
    }

    /**
     * 任务体编排：等待闸门放行（超时或被中断即退出，中断位回置交执行器收尾处理）。
     *
     * @param gate 放行闸门
     */
    private void awaitGate(CountDownLatch gate) {
        try {
            gate.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

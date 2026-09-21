package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionTaskQueue} 摄入内存队列单测（B.5）。
 * <p>覆盖：提交参数防御与幂等去重、停机拒提、出队领取命中执行 / 未命中静默丢弃、
 * 领取异常静默丢弃、排队任务撤下、在飞等待与超时、消费线程数即并发闸门与命名规范。</p>
 * <p>另覆盖三段式停机语义：首个动作清空本实例注册表键、宽限期满后对残留
 * （含仍停留在队列中排队的任务）逐个显式置失败收敛、全程不中断消费线程。</p>
 * <p>change fix-rag-concurrency-integrity 追加覆盖（登记表由 {@code Set} 改为带投递令牌的
 * {@code ConcurrentHashMap} 后）：收尾窗口内迟到投递的登记与就地补投（单次、不成环）、
 * 收尾归属校验不误删他人表项、停机收敛键集合快照仍覆盖「排队 ∪ 在飞」。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class IngestionTaskQueueTest {

    /** 测试文档主键 */
    private static final Long DOC_ID = 42L;

    /** 验证异步消费行为时的等待上限（毫秒） */
    private static final long ASYNC_TIMEOUT_MILLIS = 3000L;

    /** 停机收敛固定文案（与生产常量口径一致） */
    private static final String SHUTDOWN_ERROR_MESSAGE = "[SHUTDOWN] 服务停机中断，请重新解析";

    /** 出队领取端口 Mock */
    @Mock
    private ChunkBatchWriter chunkBatchWriter;

    /** 摄入管线执行器 Mock */
    @Mock
    private IngestionWorker ingestionWorker;

    /** 在飞任务收敛器 Mock（停机首个动作清空本实例键 + 宽限期满显式收敛残留） */
    @Mock
    private InFlightTaskConverger inFlightTaskConverger;

    /** 被测队列 */
    @InjectMocks
    private IngestionTaskQueue queue;

    @BeforeEach
    void setUp() {
        // 缺省单消费者，降低并发用例的时序噪音；专项用例自行覆写
        ReflectionTestUtils.setField(queue, "maxInflight", 1);
        lenient().when(chunkBatchWriter.markProcessing(anyLong())).thenReturn(true);
        lenient().doAnswer(invocation -> null).when(ingestionWorker).execute(anyLong());
    }

    @AfterEach
    void tearDown() {
        // 停机置位后消费线程经带超时出队轮询自然退出，避免测试线程泄漏
        queue.shutdown();
    }

    /**
     * 读取被测队列的内部状态字段。
     */
    @SuppressWarnings("unchecked")
    private <T> T readField(String fieldName) {
        return (T) ReflectionTestUtils.getField(queue, fieldName);
    }

    /**
     * 去重登记表键集合快照（＝排队中 ∪ 在飞中；登记表由 {@code Set<Long>} 改为
     * {@code ConcurrentHashMap<Long, TrackedEntry>} 后统一按键集合观测）。
     */
    private Set<Long> trackedIdsSnapshot() {
        ConcurrentHashMap<Long, ?> tracked = readField("tracked");
        return Set.copyOf(tracked.keySet());
    }

    @Test
    void should_throwIllegalArgument_when_submit_given_nullDocumentId() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> queue.submit(null));
        verifyNoQueuedTask();
    }

    @Test
    void should_acceptTaskOnce_when_submit_given_duplicateDocumentId() {
        // given：未启动消费者，任务停留在队列中

        // when
        boolean first = queue.submit(DOC_ID);
        boolean duplicate = queue.submit(DOC_ID);

        // then：重复提交幂等返回 true，但去重集与队列均只保留一份任务
        assertTrue(first);
        assertTrue(duplicate);
        assertEquals(1, trackedIdsSnapshot().size());
        assertEquals(List.of(DOC_ID), taskQueueSnapshot());
    }

    @Test
    void should_throwIllegalState_when_submit_given_shutdownQueue() {
        // given
        queue.shutdown();

        // when // then：停机拒提交由知识库侧回滚 FAILED
        assertThrows(IllegalStateException.class, () -> queue.submit(DOC_ID));
        verifyNoQueuedTask();
    }

    @Test
    void should_executeWorker_when_consumerRunning_given_markProcessingHit() {
        // given：领取命中（PENDING→PROCESSING 条件更新成功）
        when(chunkBatchWriter.markProcessing(DOC_ID)).thenReturn(true);

        // when
        queue.startConsumers();
        assertTrue(queue.submit(DOC_ID));

        // then：领取成功后同步执行完整摄入管线，收尾清理登记与去重集
        verify(chunkBatchWriter, timeout(ASYNC_TIMEOUT_MILLIS)).markProcessing(DOC_ID);
        verify(ingestionWorker, timeout(ASYNC_TIMEOUT_MILLIS)).execute(DOC_ID);
        awaitCleanup();
    }

    @Test
    void should_discardSilently_when_consumerRunning_given_markProcessingMiss() {
        // given：领取未命中（文档已被删除链 / 启动清理改写）
        when(chunkBatchWriter.markProcessing(DOC_ID)).thenReturn(false);

        // when
        queue.startConsumers();
        queue.submit(DOC_ID);

        // then：静默丢弃——不回写、不执行管线、不抛异常
        verify(chunkBatchWriter, timeout(ASYNC_TIMEOUT_MILLIS)).markProcessing(DOC_ID);
        verify(ingestionWorker, after(ASYNC_TIMEOUT_MILLIS / 3).never()).execute(any());
        awaitCleanup();
    }

    @Test
    void should_discardSilently_when_consumerRunning_given_markProcessingThrows() {
        // given：领取自身异常（如 DB 瞬断）视同未命中
        when(chunkBatchWriter.markProcessing(DOC_ID)).thenThrow(new RuntimeException("DB 瞬断"));

        // when
        queue.startConsumers();
        queue.submit(DOC_ID);

        // then：消费线程不因异常退出，任务被丢弃且无后续执行
        verify(chunkBatchWriter, timeout(ASYNC_TIMEOUT_MILLIS)).markProcessing(DOC_ID);
        verify(ingestionWorker, after(ASYNC_TIMEOUT_MILLIS / 3).never()).execute(any());
        awaitCleanup();
    }

    @Test
    void should_removeQueuedTask_when_awaitTermination_given_taskNotYetConsumed() {
        // given：未启动消费者，任务停留在队列中
        queue.submit(DOC_ID);

        // when
        boolean terminated = queue.awaitTermination(DOC_ID, Duration.ofSeconds(1L));

        // then：排队任务被撤下并确认，去重集同步释放
        assertTrue(terminated);
        assertTrue(taskQueueSnapshot().isEmpty());
        assertTrue(trackedIdsSnapshot().isEmpty());
        verify(ingestionWorker, never()).execute(any());
    }

    @Test
    void should_returnTrueWithoutDelegation_when_awaitTermination_given_nullDocumentId() {
        // when // then：空文档ID视为无任务
        assertTrue(queue.awaitTermination(null, Duration.ofSeconds(1L)));
        verifyNoQueuedTask();
    }

    @Test
    void should_confirmWithoutTask_when_awaitTermination_given_unknownDocumentId() {
        // given：另一文档在排队，目标文档无任何任务
        queue.submit(99L);

        // when
        boolean terminated = queue.awaitTermination(DOC_ID, Duration.ZERO);

        // then：两处皆无 → 立即确认，不影响他任务
        assertTrue(terminated);
        assertEquals(1, taskQueueSnapshot().size());
    }

    @Test
    void should_reportFalseThenConfirm_when_awaitTermination_given_inFlightTaskFinishes() throws Exception {
        // given：worker 执行阻塞，任务处于在飞
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            return null;
        }).when(ingestionWorker).execute(DOC_ID);
        queue.startConsumers();
        queue.submit(DOC_ID);
        awaitInFlightRegistered();

        // when①：在飞未结束，短等待超时返回 false（调用方 WARN 留痕后继续清退）
        boolean timedOut = queue.awaitTermination(DOC_ID, Duration.ofMillis(50L));

        // then①
        assertFalse(timedOut, "在飞任务未终止时等待必须超时返回 false");

        // when②：放行 worker 并等待收尾
        release.countDown();
        verify(ingestionWorker, timeout(ASYNC_TIMEOUT_MILLIS)).execute(DOC_ID);
        awaitCleanup();

        // then②：收尾注册表已清空，再次确认立即返回 true
        assertTrue(queue.awaitTermination(DOC_ID, Duration.ZERO));
    }

    /**
     * 场景（3.5）：生效消费者数 = 配置 {@code app.rag.ingestion.max-inflight}=2，投 3 篇文档。
     * 预期：同时消费的消费者数恰为生效配置值 2（第 3 篇因超上限排队），消费者经命名虚拟线程承载、
     * 线程名可辨识为摄入队列消费者（{@code rag-ingestion-queue-N}）——数量与命名均由受管执行器统一保证。
     */
    @Test
    void should_useConfiguredConsumerCount_when_startConsumers_given_maxInflight() throws Exception {
        // given
        ReflectionTestUtils.setField(queue, "maxInflight", 2);
        when(chunkBatchWriter.markProcessing(anyLong())).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> consumerThreadNames = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            consumerThreadNames.add(Thread.currentThread().getName());
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行消费者");
            active.decrementAndGet();
            return null;
        }).when(ingestionWorker).execute(anyLong());

        // when
        queue.startConsumers();
        queue.startConsumers();
        queue.submit(42L);
        queue.submit(43L);
        queue.submit(44L);
        awaitActiveCount(active, 2);
        TimeUnit.MILLISECONDS.sleep(300L);

        // then：同时消费数 = 生效配置 2（第 3 篇被挡住）；线程命名可辨识
        assertEquals(2, peak.get(), "同时消费的消费者数应等于 max-inflight 生效值");
        assertEquals(2, consumerThreadNames.size());
        assertTrue(consumerThreadNames.stream().allMatch(name -> name.startsWith("rag-ingestion-queue-")),
                "消费者线程应语义化命名，实际：" + consumerThreadNames);

        // 收尾放行
        release.countDown();
    }

    @Test
    void should_clearRegistryFirst_when_shutdown_given_inFlightTasks() {
        // given：未启动消费者，任务停留在队列中
        queue.submit(DOC_ID);

        // when：停机（在飞集合为空，宽限等待立即结束，不真实等待 30 秒）
        queue.shutdown();

        // then：停机第一时间清空本实例全部注册表键（消除滚动发布期间新旧进程并存的误判）
        verify(inFlightTaskConverger, atLeastOnce()).clearInstanceRegistryQuietly();
    }

    @Test
    void should_convergeQueuedTask_when_shutdown_given_taskStillQueued() {
        // given：不启动消费线程，任务停留在队列（去重集有值、在飞集合为空）
        queue.submit(DOC_ID);
        assertTrue(trackedIdsSnapshot().contains(DOC_ID));

        // when：停机（在飞集合为空，宽限等待立即结束，不真实等待 30 秒）
        queue.shutdown();

        // then：队列中排队的任务同样被显式置失败，不因注册表键已清空而漏收敛
        verify(inFlightTaskConverger, atLeastOnce())
                .converge(InFlightTaskType.DOC_INGESTION, DOC_ID, SHUTDOWN_ERROR_MESSAGE);
    }

    @Test
    void should_clearRegistryBeforeGraceWaitAndConvergence_when_shutdown_given_queuedResidue() {
        // given：任务停留在队列中（不启动消费线程 → 在飞集合为空，宽限循环条件立即为假，不真实等待 30 秒）
        queue.submit(DOC_ID);

        // when
        queue.shutdown();

        // then：清空本实例键是停机链首个动作，先于宽限等待及其后的显式收敛；
        // 在飞集合为空时宽限循环立即结束，故本 InOrder 即证明「清空键先于宽限等待开始」
        InOrder inOrder = inOrder(inFlightTaskConverger);
        inOrder.verify(inFlightTaskConverger).clearInstanceRegistryQuietly();
        inOrder.verify(inFlightTaskConverger)
                .converge(InFlightTaskType.DOC_INGESTION, DOC_ID, SHUTDOWN_ERROR_MESSAGE);
    }

    @Test
    void should_notConvergeTask_when_shutdown_given_taskFinishedWithinGrace() throws Exception {
        // given：worker 执行阻塞，任务处于在飞（消费线程持有其收尾闩）
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            return null;
        }).when(ingestionWorker).execute(DOC_ID);
        queue.startConsumers();
        queue.submit(DOC_ID);
        awaitInFlightRegistered();

        // when：另一线程发起停机——停机链首动作清空本实例键后进入宽限等待
        //      （此处手动起线程仅为测试侧停机触发器：停机须在独立线程推进，令主测试线程可观测其在飞
        //      阻塞期间的中间态；生产代码建线程已收敛至受管执行器，见类内 startConsumers）
        Thread shutdownThread = Thread.ofVirtual().start(queue::shutdown);
        verify(inFlightTaskConverger, timeout(ASYNC_TIMEOUT_MILLIS)).clearInstanceRegistryQuietly();

        // then①：键已清空而任务仍在飞，停机停在宽限等待中，尚未对任何任务做置失败收敛
        assertTrue(shutdownThread.isAlive(), "停机应在宽限内等待在飞任务自然结束");
        verify(inFlightTaskConverger, never())
                .converge(eq(InFlightTaskType.DOC_INGESTION), eq(DOC_ID), any());

        // when②：任务在宽限期内自然结束（execute 正常返回，收尾移除去重集与在飞登记）
        release.countDown();
        shutdownThread.join(ASYNC_TIMEOUT_MILLIS);

        // then②：停机等待其结束后直接收尾——不置失败收敛、不回写失败，文档保持成功态且 ID 已被移出集合
        assertFalse(shutdownThread.isAlive(), "停机宽限内等待未在任务结束后及时收尾");
        verify(inFlightTaskConverger, never())
                .converge(eq(InFlightTaskType.DOC_INGESTION), eq(DOC_ID), any());
        verify(chunkBatchWriter, never()).markFailed(any(), any());
        assertTrue(trackedIdsSnapshot().isEmpty());
    }

    @Test
    void should_treatMemberRemovalAsNoOp_when_shutdown_given_taskFinishedBeforeGrace() {
        // given：消费线程已启动，任务在停机前自然结束并自我收尾（去重集 / 在飞登记均已不持有该 ID）
        queue.startConsumers();
        assertTrue(queue.submit(DOC_ID));
        verify(ingestionWorker, timeout(ASYNC_TIMEOUT_MILLIS)).execute(DOC_ID);
        awaitCleanup();
        assertFalse(trackedIdsSnapshot().contains(DOC_ID), "任务应先自行收尾移除成员");

        // when：停机（键已清空 / 成员已不存在，对已不存在的集合执行移除为无害空操作）
        assertDoesNotThrow(queue::shutdown);

        // then：该任务不受停机影响——保持成功态（无失败回写）、不被显式收敛
        verify(chunkBatchWriter, never()).markFailed(any(), any());
        verify(inFlightTaskConverger, never())
                .converge(eq(InFlightTaskType.DOC_INGESTION), eq(DOC_ID), any());
    }

    /**
     * 场景（3.6）：消费者已启动且某任务在飞执行中，另一线程发起停机。
     * 预期：停机链仅对消费者执行器 {@code shutdown()}（绝不用 {@code shutdownNow()}），
     * 运行中的在飞任务全程不被中断（其线程结束时中断标记为 false）；宽限内正常结束者仍走成功收尾
     * ——队列侧表现为不回写 FAILED、不显式收敛，去重集被任务自身清理。
     */
    @Test
    void should_notInterruptInFlightTask_when_shutdown_given_taskRunning() throws Exception {
        // given：worker 执行阻塞在飞，记录其结束瞬间的中断标记
        when(chunkBatchWriter.markProcessing(DOC_ID)).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> interruptedAtFinish = new AtomicReference<>();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            interruptedAtFinish.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(ingestionWorker).execute(DOC_ID);
        queue.startConsumers();
        queue.submit(DOC_ID);
        assertTrue(entered.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "任务应已进入在飞执行");

        // when：另一线程发起停机（执行器仅 shutdown、不打断在飞），随后在宽限内放行任务自然结束
        Thread shutdownThread = Thread.ofVirtual().start(queue::shutdown);
        verify(inFlightTaskConverger, timeout(ASYNC_TIMEOUT_MILLIS)).clearInstanceRegistryQuietly();
        release.countDown();
        shutdownThread.join(ASYNC_TIMEOUT_MILLIS);

        // then①：在飞任务线程在停机全程未被中断，停机等待其结束后收尾
        assertFalse(shutdownThread.isAlive(), "停机应在宽限内等待在飞任务自然结束后收尾");
        assertEquals(Boolean.FALSE, interruptedAtFinish.get(), "停机不得中断在飞任务线程");
        // then②：宽限内正常结束者仍写成功终态——队列侧不回写失败、不显式收敛，去重集被自身清理
        verify(chunkBatchWriter, never()).markFailed(any(), any());
        verify(inFlightTaskConverger, never())
                .converge(eq(InFlightTaskType.DOC_INGESTION), eq(DOC_ID), any());
        assertTrue(trackedIdsSnapshot().isEmpty());
    }

    // ==================== 投递去重与归属清理（change fix-rag-concurrency-integrity / D3、D4、D5） ====================

    /**
     * 场景（spec ingestion-resubmission-integrity）：任务在飞期间（worker 已/未写完终态、
     * 去重表项尚未清理的收尾窗口）用户重新解析迟到投递。
     * 预期：迟到投递幂等返回 {@code true} 且登记为待补投（不被吞）；在飞任务收尾归属清理
     * 成功后就地补投一次，同一文档被第二次执行——文档不会悬挂 {@code PENDING}；
     * 补投仅一次（不累计、不成环），两轮收尾后登记表与在飞注册表均清空。
     */
    @Test
    void should_requeueOnCleanup_when_submit_given_dedupHitWhileInFlight() throws Exception {
        // given：worker 执行阻塞，文档处于在飞（表项未清理＝迟到投递可命中的去重窗口）
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            return null;
        }).when(ingestionWorker).execute(DOC_ID);
        queue.startConsumers();
        queue.submit(DOC_ID);
        awaitInFlightRegistered();

        // when①：在飞期间迟到投递
        boolean late = queue.submit(DOC_ID);

        // then①：返回 true 的收紧语义——「已登记待补投、收尾必补投」；不产生第二份排队条目
        assertTrue(late, "去重命中仍返回 true：语义为『该文档后续确实会被处理』");
        assertTrue(taskQueueSnapshot().isEmpty(), "去重命中的迟到投递 MUST NOT 直接追加队列条目（原任务仍在飞）");
        assertEquals(1, trackedIdsSnapshot().size(), "迟到投递只登记在原表项上，不新增表项");

        // when②：放行在飞任务，收尾归属清理后应就地补投
        release.countDown();

        // then②：补投使同一文档被第二次完整执行；首轮收尾后不再有第三次执行（单次补投）
        verify(ingestionWorker, timeout(ASYNC_TIMEOUT_MILLIS).times(2)).execute(DOC_ID);
        verify(chunkBatchWriter, timeout(ASYNC_TIMEOUT_MILLIS).atLeast(2)).markProcessing(DOC_ID);
        awaitCleanup();
        verify(ingestionWorker, after(ASYNC_TIMEOUT_MILLIS / 3).times(2)).execute(DOC_ID);
    }

    /**
     * 场景（spec 归属权 / design D3）：在飞任务执行期间，其去重表项被替换为
     * 「另一投递周期写入的新表项」（模拟旧集合语义下会被误删的形态）。
     * 预期：收尾以 {@code remove(documentId, 自己的令牌)} 归属校验失手为「不误删他人表项」——
     * 新表项完整保留、继续受幂等保护；归属未成立时不触发补投（不误增执行次数）。
     */
    @Test
    void should_notRemoveNewerEntry_when_finishProcessing_given_entryReplacedByAnotherSubmission() throws Exception {
        // given：worker 执行阻塞，文档在飞且出队方已持有自己的表项令牌
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            return null;
        }).when(ingestionWorker).execute(DOC_ID);
        queue.startConsumers();
        queue.submit(DOC_ID);
        awaitInFlightRegistered();

        // given：测试替身——把该文档的表项值替换为他物（旧无条件 remove 语义下等效于
        // 「他人新写入的表项被旧任务收尾抹除」的场景）
        ConcurrentHashMap<Long, Object> tracked = readField("tracked");
        Object foreignEntry = new Object();
        tracked.put(DOC_ID, foreignEntry);

        // when：放行在飞任务进入收尾
        release.countDown();
        verify(ingestionWorker, timeout(ASYNC_TIMEOUT_MILLIS)).execute(DOC_ID);
        assertTrue(queue.awaitTermination(DOC_ID, Duration.ofMillis(ASYNC_TIMEOUT_MILLIS)),
                "在飞收尾闩应随任务结束归零");
        // 归属清理段紧随闩清理之后，留裕量等待消费线程走完 finally
        TimeUnit.MILLISECONDS.sleep(100L);

        // then①：归属校验失手不误删——他人表项原样在位
        assertSame(foreignEntry, tracked.get(DOC_ID), "旧任务收尾 MUST NOT 抹除非自己写入的表项");

        // then②：归属清理未成功即不触发补投（新表项自证「已被登记处理」，无需再补）
        verify(ingestionWorker, times(1)).execute(DOC_ID);
    }

    /**
     * 场景（design D5）：改表（{@code Set} → 登记表键集合）后停机显式收敛的残留快照
     * 仍覆盖「排队中 ∪ 在飞中」。
     * 预期：一个文档在飞（收尾前被快照持有）、一个停留排队，两者均被逐个显式置失败收敛，
     * 语义与原 {@code trackedIds} 快照等价。
     */
    @Test
    void should_convergeAllTrackedIds_when_shutdown_given_queuedAndInFlightTasks() throws Exception {
        // given：单消费者——首文档出队在飞（执行阻塞、表项未清理），次文档停留队列
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(release.await(ASYNC_TIMEOUT_MILLIS * 4, TimeUnit.MILLISECONDS), "测试收尾须放行在飞任务");
            return null;
        }).when(ingestionWorker).execute(anyLong());
        queue.startConsumers();
        queue.submit(DOC_ID);
        awaitInFlightRegistered();
        queue.submit(43L);

        // when：另一线程停机；清空注册表键后进入宽限等待，
        // 测试侧中断宽限循环——复现「宽限期满仍有在飞」的收敛时点（不真实等待 30 秒）
        Thread shutdownThread = Thread.ofVirtual().start(queue::shutdown);
        verify(inFlightTaskConverger, timeout(ASYNC_TIMEOUT_MILLIS)).clearInstanceRegistryQuietly();
        shutdownThread.interrupt();

        // then：在飞（表项未清理）与排队两个文档 ID 均进入残留快照并被显式收敛
        verify(inFlightTaskConverger, timeout(ASYNC_TIMEOUT_MILLIS))
                .converge(InFlightTaskType.DOC_INGESTION, DOC_ID, SHUTDOWN_ERROR_MESSAGE);
        verify(inFlightTaskConverger, timeout(ASYNC_TIMEOUT_MILLIS))
                .converge(InFlightTaskType.DOC_INGESTION, 43L, SHUTDOWN_ERROR_MESSAGE);

        // 收尾放行，消费循环自然退出，停机链走有界确认
        release.countDown();
        shutdownThread.join(ASYNC_TIMEOUT_MILLIS);
    }

    /**
     * 读取内部任务队列快照（避免直接暴露生产 API）。
     */
    private List<Long> taskQueueSnapshot() {
        java.util.Queue<Long> taskQueue = readField("taskQueue");
        return List.copyOf(taskQueue);
    }

    /** 自旋等待在飞消费者数达到期望值（用于校验消费者数量等于生效配置）。 */
    private void awaitActiveCount(AtomicInteger active, int expected) {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (active.get() >= expected) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("在限定时间内未观察到 " + expected + " 个消费者并发执行");
    }

    /** 自旋等待指定文档登记进入在飞注册表。 */
    private void awaitInFlightRegistered() throws InterruptedException {
        awaitInFlightRegistered(DOC_ID);
    }

    /** 自旋等待指定文档登记进入在飞注册表。 */
    private void awaitInFlightRegistered(Long documentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ConcurrentHashMap<Long, CountDownLatch> inFlight = readField("inFlight");
            if (inFlight.containsKey(documentId)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        fail("在限定时间内未观察到任务进入在飞注册表, documentId=" + documentId);
    }

    /** 自旋等待收尾清理（去重登记表与在飞注册表均不再持有 DOC_ID）。 */
    private void awaitCleanup() {
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ConcurrentHashMap<Long, CountDownLatch> inFlight = readField("inFlight");
            if (!trackedIdsSnapshot().contains(DOC_ID) && !inFlight.containsKey(DOC_ID)) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertFalse(trackedIdsSnapshot().contains(DOC_ID), "收尾未清理去重登记表");
        ConcurrentHashMap<Long, CountDownLatch> residual = readField("inFlight");
        assertFalse(residual.containsKey(DOC_ID), "收尾未清理在飞注册表");
    }

    /** 断言无任何任务入队且无领取交互（参数防御类用例）。 */
    private void verifyNoQueuedTask() {
        assertTrue(trackedIdsSnapshot().isEmpty());
        verify(chunkBatchWriter, never()).markProcessing(any());
    }
}

package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import jakarta.annotation.PreDestroy;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 摄入任务内存队列（进程内调度；在飞归属由跨进程在飞任务注册表承载）。
 * <p>文档成功落库 * （状态 {@code PENDING} = 已入队待解析）即经 {@link #submit(Long)} 入队；
 * 由受管执行器（thread-per-task 虚拟线程工厂，线程名 {@code rag-ingestion-queue-N}）承载的
 * （默认 5）个常驻消费者消费，并发上限即消费者数量——同时在飞的整条摄入管线数天然不超过该值，
 * 超限任务在队列中等待，不丢弃、不拒绝。</p>
 *
 * <p>出队领取：消费线程取出任务后先经 {@link ChunkBatchWriter#markProcessing(Long)}
 * 条件更新 {@code PENDING → PROCESSING} 确认独占，未命中（文档已被删除链置 DELETING、
 * 被本实例启动恢复置 FAILED 等）即静默丢弃、不产生任何回写；命中后交由
 * {@link IngestionWorker#execute(Long)} 执行完整六阶段管线（其永不抛出，收尾清理路径唯一）。</p>
 *
 * <p>幂等去重：{@code tracked} 登记表（键＝排队 ∪ 在飞的文档ID，值为 {@link TrackedEntry}）
 * 以 {@code putIfAbsent} 占位挡重复入队（挡双击重试双份入队）。表项实例身份即投递令牌，
 * 收尾经 {@code remove(documentId, 自己持有的表项)} 归属校验清理（与 {@code inFlight.remove(id, latch)}
 * 双参 CAS 对称），旧任务收尾不误删新投递写入的表项；去重命中的迟到投递在既有表项上登记
 * 待补投标记，由对应任务收尾清理成功后就地补投一次——{@code submit} 返回 {@code true}
 * 恒表达「该文档后续确实会被处理」（已入队或已登记待补投）。</p>
 *
 * <p>删除链掐灭确认（承接原调度器 {@code awaitIngestionTermination} 语义，
 * 经 {@code DefaultIngestionCancellationApi} 委托）：尚在队列中的任务可被撤下
 * （出队竞态由 {@link LinkedBlockingQueue#remove} 与消费侧天然互斥保证只有一方成功）；
 * 已在飞的任务等待其于阶段边界自灭后确认；「先登记闩、后领取」闭合注册窗口——
 * 消费线程在 markProcessing 之前登记收尾闩，删除链观察到 DELETING 必晚于领取事务提交，
 * 即便登记间隙内确认提前返回 true，后续 markProcessing 条件更新也必因状态脱离 PENDING 而未命中，
 * 不存在复活覆盖。</p>
 *
 * <p>生命周期：消费者执行器与常驻消费者在应用就绪（{@link ApplicationReadyEvent}，{@code @Order} 晚于
 * 启动恢复 {@link IngestionStartupMaintenance}）后由 {@link #startConsumers()} 创建并启动（受管形态：
 * 消费者经 {@link ExecutorService} 承载，而非业务代码手写创建线程），保证「先恢复、后消费」；
 * 就绪前到达的提交仅入队不消费。停机（{@link PreDestroy}）为四段式语义：
 * ①置停机标志拒收新提交、并对消费者执行器 {@code shutdown()}（<b>绝不用</b> {@code shutdownNow()}，
 * 停止接受新的消费者调度但不打断已运行的消费者；消费循环随之经带超时出队轮询自然退出，
 * MUST NOT 中断消费线程或任务线程，已在执行的在飞任务不被打断），并<strong>第一时间清空本实例全部注册表键</strong>
 * （先于宽限等待，消除滚动发布期间新旧进程并存造成的误判；<b>清空键不等于放弃任务</b>）；
 * ②在 {@code SHUTDOWN_GRACE_MILLIS}（30 秒）宽限内等待在飞任务自然结束（正常结束者仍置成功态）；
 * ③宽限期满后对残留（排队中 ∪ 在飞中）逐个执行「数据库状态一致性校验 → 条件置 FAILED」的显式收敛，
 * 队列中排队的 {@code PENDING} 任务同样被置失败——收敛不依赖已被清空的键凭据；
 * ④收敛后对消费者执行器做不超过剩余宽限的有界 {@code awaitTermination} 确认消费循环退出，
 * 超时仅 WARN 留痕、不中断、不阻塞收敛。
 * 停机期间拒绝新提交（抛 {@link IllegalStateException}，由知识库侧调用方回滚 FAILED）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class IngestionTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(IngestionTaskQueue.class);

    /** 消费虚拟线程名前缀（线程语义化命名：rag-ingestion-queue-N）。 */
    private static final String CONSUMER_THREAD_NAME_PREFIX = "rag-ingestion-queue-";

    /** 停机宽限等待时长（毫秒）：等待在飞任务自然结束，超时才转显式置失败收敛。 */
    private static final long SHUTDOWN_GRACE_MILLIS = 30_000L;

    /** 停机宽限期内检查在飞集合是否已空的轮询间隔（毫秒）。 */
    private static final long IN_FLIGHT_POLL_INTERVAL_MILLIS = 100L;

    /** 消费线程出队轮询超时（毫秒）：带超时轮询使停机时无需中断线程即可退出。 */
    private static final long CONSUMER_POLL_TIMEOUT_MILLIS = 200L;

    /** 停机收敛回写的失败原因：可辨识前缀 [SHUTDOWN]，提示用户手动重新解析。 */
    private static final String SHUTDOWN_ERROR_MESSAGE = "[SHUTDOWN] 服务停机中断，请重新解析";

    /** 同时在飞摄入管线上限（{@code app.rag.ingestion.max-inflight}，缺省 5），即常驻消费线程数。 */
    @Value("${app.rag.ingestion.max-inflight:5}")
    private int maxInflight;

    /** 待消费文档ID队列：无界（元素仅 documentId，量级可忽略），超限任务在此等待。 */
    private final LinkedBlockingQueue<Long> taskQueue = new LinkedBlockingQueue<>();

    /** 去重登记表：文档ID → 投递表项（覆盖排队中 ∪ 在飞中，含登记待补投的在飞表项）。 */
    private final ConcurrentHashMap<Long, TrackedEntry> tracked = new ConcurrentHashMap<>();

    /** 在飞注册表：文档ID → worker 收尾闩（消费线程在领取前登记，worker 结束或确认不启动时 countDown）。 */
    private final ConcurrentHashMap<Long, CountDownLatch> inFlight = new ConcurrentHashMap<>();

    /**
     * 常驻消费者受管执行器（{@link #startConsumers()} 中以 thread-per-task 虚拟线程工厂创建，
     * 线程名递增 {@code rag-ingestion-queue-N}）。停机仅 {@code shutdown()}（不打断运行中消费者），
     * {@code ApplicationReadyEvent} 未触发时为空（停机链据此跳过收敛）。
     */
    private volatile ExecutorService consumerExecutor;

    /** 消费线程是否已启动（幂等启动守卫，防就绪事件重放）。 */
    private volatile boolean consumersStarted;

    /** 停机标志：置位后拒绝新提交、消费循环退出。 */
    private volatile boolean shutdown;

    private final ChunkBatchWriter chunkBatchWriter;

    private final IngestionWorker ingestionWorker;

    /** 在飞任务收敛器（停机清空本实例键 + 宽限期满显式收敛残留）。 */
    private final InFlightTaskConverger inFlightTaskConverger;

    /**
     * 构造摄入任务队列。
     *
     * @param chunkBatchWriter   切片批量回写契约（出队领取端口）
     * @param ingestionWorker    摄入管线执行器（领取命中后同步执行）
     * @param inFlightTaskConverger 在飞任务收敛器（停机清空本实例键 + 宽限期满显式收敛残留）
     */
    public IngestionTaskQueue(ChunkBatchWriter chunkBatchWriter, IngestionWorker ingestionWorker,
                              InFlightTaskConverger inFlightTaskConverger) {
        this.chunkBatchWriter = chunkBatchWriter;
        this.ingestionWorker = ingestionWorker;
        this.inFlightTaskConverger = inFlightTaskConverger;
    }

    /**
     * 应用就绪后创建受管消费者执行器并启动常驻消费者（时序：晚于启动恢复，保证先恢复后消费）。
     * <p>消费者数量等于生效配置 {@code app.rag.ingestion.max-inflight}（非正数按 1 处理），
     * 由 {@link Executors#newThreadPerTaskExecutor(java.util.concurrent.ThreadFactory)} 承载
     * （每消费者一个命名虚拟线程，无池化、无队列），线程的创建、命名与回收统一交由该执行器。
     * 幂等：重复触发仅首次生效；停机后不再启动。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void startConsumers() {
        if (consumersStarted || shutdown) {
            return;
        }
        consumersStarted = true;
        int threads = effectiveConsumerCount();
        ThreadFactory consumerThreadFactory = Thread.ofVirtual().name(CONSUMER_THREAD_NAME_PREFIX, 0).factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(consumerThreadFactory);
        this.consumerExecutor = executor;
        for (int i = 0; i < threads; i++) {
            executor.execute(this::consumeLoop);
        }
        log.info("摄入任务队列已启动，消费者数量（在飞摄入并发上限）={}", threads);
    }

    /**
     * 生效消费者数量：即同时在飞摄入管线上限（{@code app.rag.ingestion.max-inflight}），非正数按 1 处理。
     *
     * @return 生效消费者数量（&gt;= 1）
     */
    private int effectiveConsumerCount() {
        return Math.max(maxInflight, 1);
    }

    /**
     * 提交一篇文档的解析任务入队（{@code IngestionTaskSubmitter} 契约的队列侧实现入口）。
     * <p>幂等且返回值语义为「该文档后续确实会被处理」：本次调用真实入队，或文档已在
     * 排队 ∪ 在飞集合中——命中时在既有表项上登记待补投标记后返回 {@code true}，
     * 对应任务收尾清理该表项后必然就地补投一份新任务（覆盖「在飞任务已写完终态、
     * 表项尚未清理」的收尾窗口，迟到投递不被吞）。
     * 停机中提交抛 {@link IllegalStateException}，由知识库侧调用方将文档回滚 FAILED。</p>
     *
     * @param documentId 目标文档主键，必填
     * @return {@code true} 表示已入队或已登记待补投（该文档必将被处理）；{@code false} 表示入队失败
     * @throws IllegalArgumentException 文档ID为空
     * @throws IllegalStateException    服务正在停机，拒收新任务
     */
    public boolean submit(Long documentId) {
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("摄入任务文档ID不能为空");
        }
        if (shutdown) {
            throw new IllegalStateException("摄入任务队列已停机，拒收解析任务: documentId=" + documentId);
        }
        TrackedEntry entry = new TrackedEntry();
        TrackedEntry existing = tracked.putIfAbsent(documentId, entry);
        if (ObjectUtils.isNotEmpty(existing)) {
            // 去重命中：迟到投递登记在既有表项上，其持有任务收尾时据此补投，本次不入队
            existing.requeueRequested = true;
            log.debug("文档解析任务已在队列或处理中，登记待补投后幂等返回, documentId={}", documentId);
            return true;
        }
        boolean offered = taskQueue.offer(documentId);
        if (!offered) {
            // 无界队列理论不可达；防御性回滚去重占位（带归属校验、仅删自己刚写入的表项），
            // 让调用方走失败回滚语义
            tracked.remove(documentId, entry);
            log.error("解析任务入队失败, documentId={}", documentId);
            return false;
        }
        log.info("解析任务已入队, documentId={}, queuedSize={}", documentId, taskQueue.size());
        return true;
    }

    /**
     * 等待指定文档的摄入任务终止确认（删除链掐灭等待，承接原调度器同名语义）。
     * <p>三段判定：①仍在队列中 → 直接撤下（remove 成功即消费方未取到）并确认；
     * ②在飞 → 等待其收尾闩归零或直至超时；③两处皆无 → 本无任务（或已收尾），立即确认。
     * 超时返回 {@code false}——调用方（删除链）WARN 留痕后继续清退，晚退 worker 必经
     * 阶段边界取消检查自灭，其迟到回写另有 {@code replaceForDocument} 的 PROCESSING 前置收口。</p>
     *
     * @param documentId 文档ID，可为空（空视为无任务，直接返回 true）
     * @param timeout    最长等待时长，可为空（空按 0 处理，仅探测不等待）
     * @return true 表示已确认无在飞/排队任务或等待内终止；false 表示超时或等待被中断
     */
    public boolean awaitTermination(Long documentId, Duration timeout) {
        if (ObjectUtils.isEmpty(documentId)) {
            return true;
        }
        if (taskQueue.remove(documentId)) {
            // 带令牌清理：与消费侧收尾同口径（remove 双参归属校验），保持登记/清理成对
            TrackedEntry entry = tracked.get(documentId);
            if (ObjectUtils.isNotEmpty(entry)) {
                tracked.remove(documentId, entry);
            }
            log.info("排队中的解析任务已随删除链撤下, documentId={}", documentId);
            return true;
        }
        CountDownLatch terminationLatch = inFlight.get(documentId);
        if (ObjectUtils.isEmpty(terminationLatch)) {
            return true;
        }
        long timeoutMillis = ObjectUtils.isEmpty(timeout) ? 0L : Math.max(timeout.toMillis(), 0L);
        try {
            return terminationLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待在飞摄入任务终止被中断, documentId={}", documentId, e);
            return false;
        }
    }

    /**
     * 四段式停机（顺序严格不可调换）：置停机标志拒收新提交并对消费者执行器 {@code shutdown()} →
     * 清空本实例注册表键 → 宽限等待在飞任务自然结束 → 宽限期满显式置失败收敛 →
     * 有界确认消费者退出 → 收尾留痕。
     * <p>全程 MUST NOT 中断消费线程或任务线程：消费者执行器仅 {@code shutdown()}（绝不用
     * {@code shutdownNow()}），已运行的消费者由停机标志驱动、经带超时出队轮询自然退出；
     * 已在执行的在飞任务不被打断，宽限内正常结束者仍置成功态。
     * 清空注册表键先于宽限等待，仅为消除滚动发布期间新旧进程并存的误判（清空键不等于放弃任务）。</p>
     */
    @PreDestroy
    public void shutdown() {
        long startNanos = System.nanoTime();
        // 1. 拒收新提交 + 停止接受新的消费者调度（仅 shutdown，不打断已运行消费者）
        shutdown = true;
        ExecutorService executor = consumerExecutor;
        if (ObjectUtils.isNotEmpty(executor)) {
            executor.shutdown();
        }
        // 2. 停机链首个动作：清空本实例全部注册表键（先于宽限等待）
        inFlightTaskConverger.clearInstanceRegistryQuietly();
        // 3. 宽限内等待在飞任务自然结束（不中断任何线程）
        awaitInFlightCompletion(SHUTDOWN_GRACE_MILLIS);
        // 4. 宽限期满后显式收敛残留（排队中 ∪ 在飞中，不依赖已被清空的键凭据）
        convergeResidueOnShutdown();
        // 5. 有界确认消费者循环退出（不超过剩余宽限，超时仅 WARN，不中断、不阻塞收敛）
        awaitConsumerTermination(executor, remainingGraceMillis(startNanos));
        // 6. 收尾留痕
        log.info("[摄入任务队列] 已停机, consumers={}, queuedSize={}", effectiveConsumerCount(), taskQueue.size());
    }

    /**
     * 有界确认消费者执行器终止：{@code shutdown()} 后等待消费循环自然退出，
     * 超时或被中断仅 WARN 留痕（MUST NOT 中断消费者、MUST NOT 阻塞收敛）。
     * <p>执行器为空（{@code ApplicationReadyEvent} 未触发即停机）时跳过。</p>
     *
     * @param executor      消费者执行器，可为空
     * @param timeoutMillis 剩余宽限上限（毫秒），非负
     */
    private void awaitConsumerTermination(ExecutorService executor, long timeoutMillis) {
        if (ObjectUtils.isEmpty(executor)) {
            return;
        }
        try {
            if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                log.warn("[摄入任务队列] 停机剩余宽限 {}ms 内消费者执行器未全部退出（不中断消费者，"
                        + "在飞残留由显式收敛处置）", timeoutMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[摄入任务队列] 等待消费者执行器终止被中断（不中断消费者）", e);
        }
    }

    /**
     * 计算停机链剩余宽限毫秒：既有宽限总时长减去已耗时，负数归零（确保不延长总停机时长）。
     *
     * @param startNanos 停机链起始 nanoTime
     * @return 剩余宽限毫秒（&gt;= 0）
     */
    private long remainingGraceMillis(long startNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return Math.max(SHUTDOWN_GRACE_MILLIS - elapsedMillis, 0L);
    }

    /**
     * 宽限内等待在飞任务自然结束：以 {@code IN_FLIGHT_POLL_INTERVAL_MILLIS} 轮询在飞集合是否为空，
     * 直至 {@code graceMillis} 耗尽。
     * <p>MUST NOT 中断消费线程或任务线程。被中断时恢复中断标志并直接返回；超时仅 WARN 留痕，
     * 由调用方转显式置失败收敛。</p>
     *
     * @param graceMillis 宽限时长（毫秒）
     */
    private void awaitInFlightCompletion(long graceMillis) {
        long startNanos = System.nanoTime();
        long graceNanos = TimeUnit.MILLISECONDS.toNanos(graceMillis);
        try {
            while (!inFlight.isEmpty() && System.nanoTime() - startNanos < graceNanos) {
                Thread.sleep(IN_FLIGHT_POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[摄入任务队列] 停机宽限等待被中断，转显式置失败收敛, inFlightSize={}", inFlight.size());
            return;
        }
        if (!inFlight.isEmpty()) {
            log.warn("[摄入任务队列] 停机宽限 {}ms 已耗尽，仍有 {} 个在飞任务未结束，转显式置失败收敛",
                    graceMillis, inFlight.size());
        }
    }

    /**
     * 宽限期满后显式收敛残留：取 {@code tracked} 键集合快照（＝排队中 ∪ 在飞中，
     * 登记待补投的在飞表项本身即属在飞集合、无需特判）逐个收敛。
     * <p>收敛经 {@link InFlightTaskConverger#converge} 完成「数据库状态一致性校验 → 条件置
     * {@code FAILED}」，不依赖注册表键凭据（键已在停机第一时间清空），故队列中排队的
     * {@code PENDING} 任务同样被置失败，不会因键已清空而漏收敛。</p>
     */
    private void convergeResidueOnShutdown() {
        Set<Long> residueIds = Set.copyOf(tracked.keySet());
        if (CollectionUtils.isEmpty(residueIds)) {
            log.info("[摄入任务队列] 停机收敛完成，无排队 / 在飞残留任务");
            return;
        }
        int failed = 0;
        for (Long documentId : residueIds) {
            if (inFlightTaskConverger.converge(InFlightTaskType.DOC_INGESTION, documentId,
                    SHUTDOWN_ERROR_MESSAGE)) {
                failed++;
            }
        }
        log.info("[摄入任务队列] 停机收敛完成，残留任务数={}，置 FAILED 数={}", residueIds.size(), failed);
    }

    /**
     * 消费主循环：带超时轮询出队 → 处理；停机置位后循环退出。
     * <p>带超时 {@code poll} 使停机时消费线程无需被中断即可退出，且不打断已在执行的在飞任务；
     * 仅在轮询被中断时恢复中断标志并退出。</p>
     */
    private void consumeLoop() {
        while (!shutdown) {
            Long documentId;
            try {
                documentId = taskQueue.poll(CONSUMER_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (ObjectUtils.isEmpty(documentId)) {
                continue;
            }
            processQuietly(documentId);
        }
    }

    /**
     * 处理单个出队任务：先登记收尾闩（先登记后领取，闭合删除链确认窗口）→
     * 条件更新领取（PENDING → PROCESSING）→ 命中则同步执行完整摄入管线；
     * 领取未命中或领取异常一律静默丢弃（不产生任何回写），收尾统一清理登记与去重表项。
     * <p>收尾清理带归属校验：只移除本次执行出队时持有的表项（投递令牌），不误删
     * 他人写入的表项；清理成功且被清表项登记了待补投（执行窗口内有迟到投递命中去重）时，
     * 就地以新表项补投一次，消除「在飞任务已写完终态、表项未清理」窗口的悬挂 {@code PENDING}。</p>
     *
     * @param documentId 出队的文档ID
     */
    private void processQuietly(Long documentId) {
        CountDownLatch terminationLatch = new CountDownLatch(1);
        inFlight.put(documentId, terminationLatch);
        // 出队即持有本次执行的投递令牌（表项实例），收尾归属校验以此为准
        TrackedEntry ownEntry = tracked.get(documentId);
        try {
            boolean marked;
            try {
                marked = chunkBatchWriter.markProcessing(documentId);
            } catch (RuntimeException e) {
                log.error("出队领取异常，丢弃该任务（文档停留原状态，可经用户重新解析再入队）, documentId={}",
                        documentId, e);
                marked = false;
            }
            if (!marked) {
                log.info("出队领取未命中（文档已被删除链/本实例启动恢复改写或不存在），静默丢弃, documentId={}", documentId);
                return;
            }
            // execute 永不向外抛出（内部已做失败回写与取消判定），异常防护由 finally 清理路径兜底
            ingestionWorker.execute(documentId);
        } finally {
            terminationLatch.countDown();
            inFlight.remove(documentId, terminationLatch);
            boolean cleaned = ObjectUtils.isNotEmpty(ownEntry) && tracked.remove(documentId, ownEntry);
            if (cleaned && ownEntry.requeueRequested) {
                requeueQuietly(documentId);
            }
        }
    }

    /**
     * 收尾补投：本次执行期间确有新投递登记时，以新表项就地重投一次
     * （单次、不累计，design D4——用户连点仅收敛为「至多再跑一遍」，不成自激环）。
     * <p>补投异常（如停机拒收）仅 WARN 留痕：残留表项/文档态由停机显式收敛或
     * 启动恢复兜底，MUST NOT 让补投失败炸掉消费循环。</p>
     *
     * @param documentId 需要补投解析任务的文档ID
     */
    private void requeueQuietly(Long documentId) {
        try {
            if (submit(documentId)) {
                log.info("收尾窗口内的迟到解析投递已就地补投, documentId={}", documentId);
            }
        } catch (RuntimeException e) {
            log.warn("收尾补投未成功（文档停留当前状态，由停机/启动收敛或用户重新解析兜底）, documentId={}",
                    documentId, e);
        }
    }

    /**
     * 去重登记表项（内部值对象）：实例身份即本次投递的令牌。
     * <p>{@code submit} 以 {@code putIfAbsent} 写入新实例占位；出队方持有自己读到的实例，
     * 收尾经 {@code remove(documentId, 自己持有的实例)} 做归属校验清理——只有写入者本人
     * 可清除，与 {@code inFlight.remove(id, latch)} 的双参 CAS 对称（design D3）。
     * 去重命中时，迟到投递在既有表项上置 {@link #requeueRequested}，
     * 收尾清理成功后据此以新表项就地补投一次（单次、不累计，见 {@code requeueQuietly}）。</p>
     */
    private static final class TrackedEntry {

        /** 待补投标记：本表项存续期间收到过迟到投递，收尾清除后须就地补投一次 */
        private volatile boolean requeueRequested;
    }
}

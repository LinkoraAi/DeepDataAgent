package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.rag.application.task.CleanupInFlightRegistry;
import com.linkroa.deepdataagent.rag.application.task.InFlightTaskConverger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 删除清退虚拟线程执行器。
 * <p>三层删除链（知识库 / 文档）共用的清退执行形态基础设施：JDK 21 thread-per-task 虚拟线程
 * （{@code Executors.newThreadPerTaskExecutor}，线程名前缀 {@value #THREAD_NAME_PREFIX}，
 * 命名工厂满足并发规范），任务经 {@link CompletableFuture#runAsync(Runnable, java.util.concurrent.Executor)}
 * <b>fire-and-forget</b> 提交——原内存有界队列 + 单消费者机器整体退役。</p>
 *
 * <p>异常语义：fire-and-forget 下异常被 future 捕获、不经 UncaughtExceptionHandler 传播，
 * 故本执行器 MUST NOT 依赖返回值或异常向提交方传播；任务体由提交方保证全量 catch + ERROR 留痕，
 * 本执行器仅兜底捕获任务体的意外抛出（保证在飞登记与配额一定释放）。</p>
 *
 * <p>并发配额：全局信号量（{@code app.rag.cleanup.max-concurrent}，缺省 4）在<b>任务体内阻塞获取</b>——
 * 虚拟线程等闩只挂起自身、不占载体线程，配额耗尽时后到任务排队等待而非被丢弃，
 * 以此保护对象存储与数据库连接池。配置非正数时回落缺省值（零许可信号量将致任务永久阻塞）。</p>
 *
 * <p>在飞去重：提交前经 {@link CleanupInFlightRegistry#tryRegister(String)} CAS 占位，
 * 返回 {@code false} 表示该资源已在飞（同库 / 同文档双任务并行被挡下），重复投递按幂等跳过处理；
 * 任务收尾（含失败与异常）在 finally 中统一 {@code unregister}。</p>
 *
 * <p>停机（显式收敛）：{@link PreDestroy} 时置停机标志拒收新提交（提交方抛 {@link IllegalStateException}），
 * <b>首个动作</b>即清空本实例注册表键（消除滚动发布期间新旧进程并存的误判），随后在宽限期
 * （{@code app.rag.cleanup.shutdown-grace-seconds}，缺省 30，配 0 表示不等待）内等待在飞任务收尾；
 * 宽限期满后按状态一致性校验对本实例未结束任务置 {@code DELETE_FAILED} 并移除注册表成员，
 * 收尾完成后才中断残留线程（被中断任务体的迟到置态因条件更新未命中而幂等空转，无害）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DeletionCleanupTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeletionCleanupTaskExecutor.class);

    /** 清退虚拟线程名前缀（线程语义化命名：kb-delete-N，便于线程 dump 归因）。 */
    private static final String THREAD_NAME_PREFIX = "kb-delete-";

    /** 全局并发配额缺省值（{@code app.rag.cleanup.max-concurrent}）。 */
    static final int DEFAULT_MAX_CONCURRENT = 4;

    /** 停机宽限期缺省值（秒，{@code app.rag.cleanup.shutdown-grace-seconds}）。 */
    static final long DEFAULT_SHUTDOWN_GRACE_SECONDS = 30L;

    /** 停机收敛文档清退的置失败留痕（与删除链既有留痕口径一致）。 */
    private static final String SHUTDOWN_DOC_CLEANUP_TRACE = "[DELETE-FAILED] step=shutdown";

    /** 停机收敛整库清退的置失败留痕。 */
    private static final String SHUTDOWN_KB_CLEANUP_TRACE = "[KB-CLEANUP] step=SHUTDOWN: 服务停机中断，请重新执行删除";

    /** 全局并发配额许可数（任务体内阻塞获取，配额耗尽即排队不丢弃）。 */
    @Value("${app.rag.cleanup.max-concurrent:4}")
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

    /** 停机宽限期秒数；0 表示不等待直接强制中断在飞任务。 */
    @Value("${app.rag.cleanup.shutdown-grace-seconds:30}")
    private long shutdownGraceSeconds = DEFAULT_SHUTDOWN_GRACE_SECONDS;

    /** 在飞去重登记器（提交前占位、任务收尾释放）。 */
    private final CleanupInFlightRegistry inFlightRegistry;

    /** 在飞任务收敛器（停机宽限期满后对未结束任务按状态一致性校验收敛）。 */
    private final InFlightTaskConverger inFlightTaskConverger;

    /** 虚拟线程执行器句柄（{@link #initialize()} 构建；停机后置空以拒收新提交）。 */
    private volatile ExecutorService executor;

    /** 全局并发配额信号量（{@link #initialize()} 按配置许可数构建）。 */
    private volatile Semaphore concurrencyQuota;

    /** 停机标志：置位后拒收新提交，在飞任务经宽限期收尾或超时被中断。 */
    private volatile boolean shutdown;

    /**
     * 构造删除清退虚拟线程执行器。
     *
     * @param inFlightRegistry       删除清退在飞去重登记器
     * @param inFlightTaskConverger  在飞任务收敛器（停机宽限期满后对未结束任务置失败并移除注册表成员）
     */
    public DeletionCleanupTaskExecutor(CleanupInFlightRegistry inFlightRegistry,
                                       InFlightTaskConverger inFlightTaskConverger) {
        this.inFlightRegistry = inFlightRegistry;
        this.inFlightTaskConverger = inFlightTaskConverger;
    }

    /**
     * 按配置构建执行器与并发配额信号量（先于任何提交发生；容器管理生命周期）。
     */
    @PostConstruct
    public void initialize() {
        int permits = maxConcurrent < 1 ? DEFAULT_MAX_CONCURRENT : maxConcurrent;
        ThreadFactory threadFactory = Thread.ofVirtual().name(THREAD_NAME_PREFIX, 0).factory();
        this.concurrencyQuota = new Semaphore(permits);
        this.executor = Executors.newThreadPerTaskExecutor(threadFactory);
        log.info("[删除清退执行器] 虚拟线程执行器已就绪: maxConcurrent={}, shutdownGraceSeconds={}",
                permits, effectiveGraceSeconds());
    }

    /**
     * 提交一个删除清退任务（fire-and-forget）。
     * <p>提交方 MUST 保证 {@code cleanupTask} 自带全量 catch 与 ERROR 留痕——本方法返回后任务
     * 在虚拟线程内异步推进，异常不经此方法传播。</p>
     *
     * @param inFlightKey 在飞去重键（{@link CleanupInFlightRegistry#kbKey(Long)} /
     *                    {@link CleanupInFlightRegistry#docKey(Long)} 构造），必填
     * @param cleanupTask 清退任务体，必填
     * @return {@code true} 表示已受理并提交异步执行；{@code false} 表示该资源已在飞被去重（幂等跳过）
     * @throws IllegalArgumentException 在飞键为空白或任务体为空
     * @throws IllegalStateException    执行器正在停机（或尚未初始化），拒收新任务
     */
    public boolean submit(String inFlightKey, Runnable cleanupTask) {
        if (StringUtils.isBlank(inFlightKey)) {
            throw new IllegalArgumentException("删除清退任务在飞键不能为空");
        }
        if (ObjectUtils.isEmpty(cleanupTask)) {
            throw new IllegalArgumentException("删除清退任务体不能为空: inFlightKey=" + inFlightKey);
        }
        ExecutorService current = this.executor;
        if (shutdown || ObjectUtils.isEmpty(current)) {
            throw new IllegalStateException("删除清退执行器已停机，拒收清退任务: inFlightKey=" + inFlightKey);
        }
        if (!inFlightRegistry.tryRegister(inFlightKey)) {
            log.debug("删除清退任务已在飞，幂等跳过重复提交: inFlightKey={}", inFlightKey);
            return false;
        }
        try {
            CompletableFuture.runAsync(() -> runQuotaGated(inFlightKey, cleanupTask), current);
        } catch (RejectedExecutionException e) {
            inFlightRegistry.unregister(inFlightKey);
            throw new IllegalStateException("删除清退执行器拒收任务: inFlightKey=" + inFlightKey, e);
        }
        log.info("删除清退任务已提交虚拟线程: inFlightKey={}", inFlightKey);
        return true;
    }

    /**
     * 停机：拒收新提交 → 第一时间清空本实例注册表键 → 宽限期内等待在飞任务收尾 →
     * 宽限期满按状态一致性校验对未结束任务置 {@code DELETE_FAILED} 并移除成员 → 收尾后中断残留线程。
     */
    @PreDestroy
    public void shutdown() {
        shutdown = true;
        // 停机链首个动作：先于宽限等待清空本实例注册表键，消除滚动发布期间新旧进程并存的误判
        inFlightTaskConverger.clearInstanceRegistryQuietly();
        ExecutorService current = this.executor;
        this.executor = null;
        if (ObjectUtils.isEmpty(current)) {
            return;
        }
        current.shutdown();
        long graceSeconds = effectiveGraceSeconds();
        try {
            if (!current.awaitTermination(TimeUnit.SECONDS.toMillis(graceSeconds), TimeUnit.MILLISECONDS)) {
                log.warn("删除清退停机宽限期（{}s）耗尽，转入显式置失败收敛：对未结束任务按状态一致性校验置 DELETE_FAILED",
                        graceSeconds);
            }
        } catch (InterruptedException e) {
            log.warn("删除清退停机等待被中断，转入显式置失败收敛：对未结束任务按状态一致性校验置 DELETE_FAILED", e);
            Thread.currentThread().interrupt();
        }
        convergeResidueOnShutdown();
        // 收敛收尾完成后才中断残留线程：被中断任务体的迟到置态因条件更新未命中而幂等空转，无害
        current.shutdownNow();
        log.info("删除清退虚拟线程执行器已停机");
    }

    /**
     * 停机收敛本实例残留：清点两个在飞键集合并逐个置失败（含状态一致性校验与成员移除）。
     * <p>宽限期满后 MUST NOT 立即中断在飞任务——必须完成全部一致性校验、置态与成员移除后方可退出。</p>
     */
    private void convergeResidueOnShutdown() {
        Set<Long> documentIds = inFlightRegistry.inFlightDocumentIds();
        int convergedDocs = 0;
        for (Long documentId : documentIds) {
            if (inFlightTaskConverger.converge(InFlightTaskType.DOC_CLEANUP, documentId, SHUTDOWN_DOC_CLEANUP_TRACE)) {
                convergedDocs++;
            }
        }
        Set<Long> kbIds = inFlightRegistry.inFlightKnowledgeBaseIds();
        int convergedKbs = 0;
        for (Long kbId : kbIds) {
            if (inFlightTaskConverger.converge(InFlightTaskType.KB_CLEANUP, kbId, SHUTDOWN_KB_CLEANUP_TRACE)) {
                convergedKbs++;
            }
        }
        log.info("[删除清退停机收敛] 停机残留收敛完成: 文档残留={}, 文档置DELETE_FAILED={}, "
                + "知识库残留={}, 知识库置DELETE_FAILED={}", documentIds.size(), convergedDocs,
                kbIds.size(), convergedKbs);
    }

    /**
     * 任务外壳：阻塞获取并发配额 → 执行任务体 → finally 归还配额并释放在飞登记。
     * <p>等待配额被中断（停机 {@code shutdownNow}）时任务体不执行，直接释放在飞登记并留痕——
     * 与「被中断任务零补偿」口径一致。</p>
     *
     * @param inFlightKey 在飞去重键
     * @param cleanupTask 清退任务体（提交方自带全量 catch）
     */
    private void runQuotaGated(String inFlightKey, Runnable cleanupTask) {
        Semaphore quota = this.concurrencyQuota;
        try {
            quota.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("删除清退任务等待并发配额时被中断，任务未执行（残留由停机收敛统一处置）: inFlightKey={}",
                    inFlightKey, e);
            inFlightRegistry.unregister(inFlightKey);
            return;
        }
        try {
            cleanupTask.run();
        } catch (RuntimeException | Error e) {
            // 兜底：任务体本应自带全量留痕，此处仅保证登记与配额一定释放、失败可归因
            log.error("删除清退任务意外抛出: inFlightKey={}", inFlightKey, e);
        } finally {
            quota.release();
            inFlightRegistry.unregister(inFlightKey);
        }
    }

    /**
     * 生效的停机宽限秒数：负数按 0（不等待）处理。
     *
     * @return 非负宽限秒数
     */
    private long effectiveGraceSeconds() {
        return Math.max(shutdownGraceSeconds, 0L);
    }
}

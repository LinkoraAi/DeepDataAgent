package com.linkroa.deepdataagent.rag.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAG 虚拟线程扇出执行器配置。
 * <p>原 {@code ragIngestionExecutor} 平台线程池已整体退役：池内 worker 在
 * {@code allOf().join()} 上阻塞等待同池排队的扇出子任务，构成结构性死锁
 * （核心线程占满 → 子任务永排队 → join 永不返回）。替代方案：</p>
 * <ul>
 *   <li>文档级执行：由 {@code IngestionTaskQueue} 的常驻消费虚拟线程承载
 *       （{@code Thread.ofVirtual().name("rag-ingestion-queue-N")}），并发上限即消费线程数
 *       （{@code app.rag.ingestion.max-inflight}），不再需要平台线程池；</li>
 *   <li>阶段内扇出（实体抽取 / 图合并的 chunk、实体、关系级并发）：共享本类提供的
 *       {@code ragFanoutExecutor} 虚拟线程执行器——每任务一虚拟线程、无队列无拒绝语义，
 *       父任务 {@code join} 等待子任务不再存在同池互占问题；LLM / 向量化等远程调用
 *       阻塞时仅挂起载体线程。真实并发度仍由各阶段既有 Semaphore 闸门约束。</li>
 * </ul>
 * <p>关闭语义：应用关闭时容器回调 {@code close()} 承接原 {@code awaitTermination} 语义，
 * 最多等待 30 秒后强制中断，避免长任务卡死停机流程。</p>
 *
 * @author DeepDataAgent
 */
@Configuration(proxyBeanMethods = false)
public class RagIngestionExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionExecutorConfig.class);

    /** 关闭宽限期：等待在途扇出任务完成的最长时间（承接原线程池 awaitTerminationSeconds=30 语义）。 */
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(30);

    /**
     * RAG 阶段内扇出共享虚拟线程执行器。
     * <p>Bean 名 {@code ragFanoutExecutor}，实体抽取与图合并服务按名注入；
     * 底层为 {@link Executors#newVirtualThreadPerTaskExecutor()}，容器销毁时经
     * {@link RagFanoutVirtualExecutor#close()} 优雅关闭。</p>
     *
     * @return 虚拟线程扇出执行器
     */
    @Bean(name = "ragFanoutExecutor")
    public RagFanoutVirtualExecutor ragFanoutExecutor() {
        return new RagFanoutVirtualExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 虚拟线程扇出执行器包装：委托每任务一虚拟线程的执行器，并显式提供带 30 秒
     * 宽限期的 {@code close()}（Spring 容器销毁回调），超时后强制中断在途任务。
     */
    static final class RagFanoutVirtualExecutor implements Executor, AutoCloseable {

        /** 被委托的虚拟线程执行器。 */
        private final ExecutorService delegate;

        /**
         * 构造扇出执行器。
         *
         * @param delegate 虚拟线程执行器（每任务一虚拟线程）
         */
        RagFanoutVirtualExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        /**
         * 提交扇出子任务（每任务一虚拟线程，无队列上限、无拒绝语义）。
         *
         * @param task 待执行任务
         */
        @Override
        public void execute(Runnable task) {
            delegate.execute(task);
        }

        /**
         * 优雅关闭：先拒收新任务，再等待宽限期；超时强制中断，等待期间被中断同样强制收敛。
         */
        @Override
        public void close() {
            delegate.shutdown();
            try {
                if (!delegate.awaitTermination(SHUTDOWN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("ragFanoutExecutor 关闭等待超时（{}s），强制中断在途扇出任务",
                            SHUTDOWN_GRACE_PERIOD.toSeconds());
                    delegate.shutdownNow();
                }
            } catch (InterruptedException e) {
                delegate.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

package com.linkroa.deepdataagent.rag.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 删除清退执行端执行器与策略配置。
 *
 * <p><strong>配置项清单</strong>（全部带缺省值，未在 {@code application.yaml} 中显式配置即可运行；
 * 本类是这些键的<b>文档与生效值观测点</b>，各组件按同名键以 {@code @Value} 就地绑定缺省值）：</p>
 * <ul>
 *   <li>{@code app.rag.cleanup.max-concurrent}（缺省 4）：删除清退虚拟线程执行器的全局并发配额
 *       （信号量许可数，配额耗尽时后到任务在虚拟线程内排队等待而非被丢弃）；非正数回落缺省值；</li>
 *   <li>{@code app.rag.cleanup.shutdown-grace-seconds}（缺省 30）：停机时等待在飞清退任务自然收尾的宽限期；
 *       宽限期满后先按状态一致性校验收敛残留（置 {@code DELETE_FAILED} 并移除在飞成员），
 *       收尾后才中断残留线程（不再依赖启动重触发兜底）；</li>
 *   <li>{@code app.rag.cleanup.batch-size}（缺省 500）：KB 自有数据分批清退的单批条数（事务规范 500~1000）。</li>
 * </ul>
 *
 * <p>线程形态：清退侧为 JDK 21 thread-per-task 虚拟线程执行器
 * （{@code Executors.newThreadPerTaskExecutor}，线程名 {@code kb-delete-N}，{@code CompletableFuture#runAsync}
 * fire-and-forget + 在飞登记去重）——原「有界队列 + 单消费者 + 步骤退避重试」机器与
 * {@code app.rag.cleanup.{max-inflight,queue-capacity,retry-*}} 键一并退役。本类不声明
 * {@code Executor} 类型 Bean；「零常驻定时任务」约束在此保持不变；跨实例的任务互斥由数据库条件更新承担，
 * 本类不承载实例归属假设。</p>
 *
 * @author DeepDataAgent
 */
@Configuration(proxyBeanMethods = false)
public class RagCleanupExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagCleanupExecutorConfig.class);

    /** 删除清退全局并发配额（{@code app.rag.cleanup.max-concurrent}）。 */
    @Value("${app.rag.cleanup.max-concurrent:4}")
    private int cleanupMaxConcurrent;

    /** 删除清退停机宽限期秒数（{@code app.rag.cleanup.shutdown-grace-seconds}）。 */
    @Value("${app.rag.cleanup.shutdown-grace-seconds:30}")
    private long cleanupShutdownGraceSeconds;

    /** KB 自有数据分批清退单批条数（{@code app.rag.cleanup.batch-size}）。 */
    @Value("${app.rag.cleanup.batch-size:500}")
    private int cleanupBatchSize;

    /**
     * 启动期打印生效的清退配置，作为删除清退执行端唯一运维观测点
     * （清退并发配额决定对象存储与连接池压力）。
     */
    @PostConstruct
    public void logEffectiveSettings() {
        log.info("RAG 清退执行端配置生效: cleanup[maxConcurrent={}, graceSeconds={}, batchSize={}]",
                cleanupMaxConcurrent, cleanupShutdownGraceSeconds, cleanupBatchSize);
    }
}
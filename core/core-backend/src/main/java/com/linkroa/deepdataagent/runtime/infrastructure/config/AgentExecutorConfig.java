package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Agent 运行时执行器配置。
 * <p>提供虚拟线程按需线程池（Java 21 Loom）与 Reactor 阻塞调度器：</p>
 * <ul>
 *   <li>{@code agentVirtualExecutor}：托管 Agent 事件流的阻塞型等待（{@code completion().get()}）
 *       与持久化 / 广播等下游编排，阻塞时虚拟线程让出 OS 线程（Java 21 Loom 的 M:N 调度），
 *       tomcat worker 线程永不等待 LLM 流；</li>
 *   <li>{@code agentBlockingScheduler}：{@code publishOn} 调度器，将 SDK 事件产生线程与下游
 *       doOnNext 编排（DB 落库 / SSE 广播 / span 追踪）解耦。复用 Reactor「包裹阻塞调用」的
 *       {@link Schedulers#newBoundedElastic(int, int, ThreadFactory, int)}，线程工厂采用
 *       {@link Thread#ofVirtual()} 虚拟线程（thread-per-task），配合有界任务队列提供背压，
 *       保证事件产生不被持久化 IO 卡住。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AgentExecutorConfig {

    /** 阻塞调度器线程上限：虚拟线程，放开至整型上限近似 thread-per-task */
    private static final int BLOCKING_SCHEDULER_MAX_THREADS = Integer.MAX_VALUE;

    /** 阻塞调度器任务队列上限：超出即背压，保护内存并给上游反压 */
    private static final int BLOCKING_SCHEDULER_QUEUE_CAP = 100_000;

    /** 阻塞调度器空闲线程存活秒数：虚拟线程创建廉价，仍收敛空闲 worker */
    private static final int BLOCKING_SCHEDULER_TTL_SECONDS = 60;

    /**
     * 虚拟线程执行器：托管 Agent 事件流的阻塞型等待（{@code completion().get()}）。
     * <p>thread-per-task 语义：每任务创建一个虚拟线程，无池上限、无平台线程占用；
     * 线程名带 {@code agent-exec-} 前缀，便于线程 dump 时与阻塞调度器、SDK 线程区分。</p>
     */
    @Bean(name = "agentVirtualExecutor", destroyMethod = "close")
    public ExecutorService agentVirtualExecutor() {
        ThreadFactory threadFactory = Thread.ofVirtual().name("agent-exec-", 0).factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    /**
     * 阻塞调度器：承载 {@code publishOn} 下游的阻塞落库 / 广播 / span 编排。
     * <p>采用 Reactor「包裹阻塞调用」的 {@link Schedulers#newBoundedElastic(int, int, ThreadFactory, int)}，
     * 线程工厂为 Java 21 虚拟线程（thread-per-task，阻塞时让出载体线程），线程上限放开至
     * {@code Integer.MAX_VALUE}、任务队列有界（{@code 100_000}），
     * 规避 {@code Schedulers.fromExecutor} 无背压 / 每任务新线程导致 SSE 流停住的缺陷。
     * 独立实例非共享 {@code boundedElastic()}，故声明 {@code destroyMethod = "dispose"} 在容器关闭时释放。</p>
     */
    @Bean(name = "agentBlockingScheduler", destroyMethod = "dispose")
    public Scheduler agentBlockingScheduler() {
        // 虚拟线程 thread-per-task：阻塞调用在虚拟线程内让出载体线程（Java 21 Loom M:N）
        ThreadFactory threadFactory = Thread.ofVirtual().name("agent-blocking-", 0).factory();
        return Schedulers.newBoundedElastic(
                BLOCKING_SCHEDULER_MAX_THREADS,
                BLOCKING_SCHEDULER_QUEUE_CAP,
                threadFactory,
                BLOCKING_SCHEDULER_TTL_SECONDS);
    }
}
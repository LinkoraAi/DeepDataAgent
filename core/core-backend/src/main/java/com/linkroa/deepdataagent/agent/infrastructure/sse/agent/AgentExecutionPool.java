package com.linkroa.deepdataagent.agent.infrastructure.sse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 执行池
 * <p>管理并发 Agent 执行，使用 Java 21 Virtual Threads 支持大量并发。
 * 设活跃会话上限防止资源耗尽。</p>
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>execute(sessionId, Runnable) — 在 Virtual Thread 中执行 Agent</li>
 *   <li>onComplete(sessionId) — 分析完成时更新活跃会话计数器</li>
 *   <li>onCancel(sessionId) — 分析取消时更新活跃会话计数器</li>
 *   <li>活跃会话上限检查 — maxActiveSessions 达上限时拒绝新分析请求</li>
 *   <li>getActiveSessionCount() — 获取当前活跃会话数</li>
 * </ul>
 *
 * <p>线程安全：使用 {@link AtomicInteger} 作为活跃会话计数器，所有方法均为线程安全。</p>
 */
@Component
public class AgentExecutionPool {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionPool.class);

    /** 活跃会话计数器 */
    private final AtomicInteger activeSessionCount = new AtomicInteger(0);

    /** 执行池配置 */
    private final AgentExecutionPoolProperties properties;

    /** 虚拟线程执行器 */
    private final ExecutorService executor;

    /**
     * 构造方法
     *
     * @param properties Agent 执行池配置
     */
    public AgentExecutionPool(AgentExecutionPoolProperties properties) {
        this.properties = properties;
        if (properties.virtualThreads()) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            log.info("AgentExecutionPool initialized: maxActiveSessions={}, using Virtual Threads",
                    properties.maxActiveSessions());
        } else {
            this.executor = Executors.newCachedThreadPool();
            log.info("AgentExecutionPool initialized: maxActiveSessions={}, using Cached Thread Pool",
                    properties.maxActiveSessions());
        }
    }

    /**
     * 在虚拟线程中执行 Agent 分析任务
     * <p>检查是否达到活跃会话上限，未达到则递增计数器并提交任务到虚拟线程执行器。</p>
     *
     * @param sessionId 会话 ID
     * @param task Agent 执行任务
     * @return true 如果任务已提交执行，false 如果达到上限拒绝执行
     */
    public boolean execute(String sessionId, Runnable task) {
        if (activeSessionCount.get() >= properties.maxActiveSessions()) {
            log.warn("Agent execution pool exhausted: maxActiveSessions={}, current={}",
                    properties.maxActiveSessions(), activeSessionCount.get());
            return false;
        }

        activeSessionCount.incrementAndGet();
        log.info("Agent execution started: sessionId={}, activeCount={}",
                sessionId, activeSessionCount.get());

        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Agent execution failed for sessionId={}", sessionId, e);
            } finally {
                onComplete(sessionId);
            }
        });

        return true;
    }

    /**
     * 分析完成时回调
     * <p>递减活跃会话计数器。</p>
     *
     * @param sessionId 会话 ID
     */
    public void onComplete(String sessionId) {
        int current = activeSessionCount.decrementAndGet();
        log.info("Agent execution completed: sessionId={}, activeCount={}", sessionId, current);
    }

    /**
     * 分析取消时回调
     * <p>递减活跃会话计数器。</p>
     *
     * @param sessionId 会话 ID
     */
    public void onCancel(String sessionId) {
        int current = activeSessionCount.decrementAndGet();
        log.info("Agent execution cancelled: sessionId={}, activeCount={}", sessionId, current);
    }

    /**
     * 获取当前活跃会话数
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        return activeSessionCount.get();
    }
}
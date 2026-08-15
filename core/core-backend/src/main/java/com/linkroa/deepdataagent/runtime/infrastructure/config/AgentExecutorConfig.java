package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Agent 运行时执行器配置。
 * <p>提供虚拟线程按需线程池（Java 21 Loom）及其 Reactor 调度器封装：</p>
 * <ul>
 *   <li>{@code agentVirtualExecutor}：托管 Agent 事件流的阻塞型等待（{@code completion().get()}）
 *       与持久化 / 广播等下游编排，阻塞时虚拟线程让出 OS 线程（Java 21 Loom 的 M:N 调度），
 *       tomcat worker 线程永不等待 LLM 流；</li>
 *   <li>{@code agentVirtualScheduler}：{@code publishOn} 调度器，将 SDK 事件产生线程与下游
 *       doOnNext 编排（DB 落库 / SSE 广播 / span 追踪）解耦，保证事件产生不被持久化 IO 卡住。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AgentExecutorConfig {

    /**
     * 虚拟线程执行器：每任务创建虚拟线程，无池上限、无平台线程占用。
     */
    @Bean(name = "agentVirtualExecutor", destroyMethod = "close")
    public Executor agentVirtualExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 虚拟线程 Reactor 调度器（复用同一执行器，{@code publishOn} 在此调度下游编排）。
     */
    @Bean(name = "agentVirtualScheduler", destroyMethod = "dispose")
    public Scheduler agentVirtualScheduler(java.util.concurrent.Executor agentVirtualExecutor) {
        return Schedulers.fromExecutor(agentVirtualExecutor);
    }
}
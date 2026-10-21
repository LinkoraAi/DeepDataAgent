package com.linkroa.deepdataagent.rag.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检索答案流执行器配置。
 * <p>答案端点建流后整段链路（Stage 0~4 + Stage 5）在虚拟线程中执行：这些阶段包含多次
 * 阻塞式远程调用（模型、向量化、重排），用虚拟线程承载可在不占用平台线程池的前提下
 * 支持并发连接。执行器与摄入扇出执行器分离，避免长连接流量挤占摄入任务。</p>
 *
 * @author DeepDataAgent
 */
@Configuration(proxyBeanMethods = false)
public class RetrievalStreamExecutorConfig {

    /**
     * RAG 检索答案流执行器（每连接一虚拟线程，容器销毁时关闭）。
     *
     * @return 虚拟线程执行器
     */
    @Bean(name = "ragRetrievalStreamExecutor", destroyMethod = "close")
    public ExecutorService ragRetrievalStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
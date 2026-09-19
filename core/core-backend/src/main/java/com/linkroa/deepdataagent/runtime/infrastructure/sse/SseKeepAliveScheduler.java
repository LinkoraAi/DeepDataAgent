package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 保活心跳调度器（承接原 {@code SseEmitterRegistry} 全局心跳的<b>保活职责</b>）。
 * <p>Spring WebMvc 7 的 {@code SseEmitter} 超时在初始化时固定、不支持 {@code extendTimeout}，
 * 保活注释帧（{@code heartbeat}）是空闲期防容器 / 代理回收长连接的唯一手段，属
 * 客户端可观测行为。句柄降为会话运行时单主（D18）后，本调度器每 tick 遍历
 * {@link SessionRuntimeRegistry#activeContexts()}（句柄唯一所有者）取 {@code connection().heartbeat()} 下发——
 * 遍历集合规模与原注册表 map 同阶（无连接的空闲句柄在 {@code heartbeat()} 首行直接返回）。</p>
 * <p>死连接回收不再依赖第二份 map 的 {@code removeIf}：句柄内 {@code send} 失败即
 * {@code removeConnection}（既有路径），句柄本身的回收归会话运行时生命周期。</p>
 */
@Slf4j
@Component
public class SseKeepAliveScheduler {

    /** 心跳周期下限（防止 sse-timeout 配置过小时周期被推导为 0） */
    private static final Duration HEARTBEAT_MIN_INTERVAL = Duration.ofSeconds(10);

    /** 心跳周期直读全局配置（配置类直注，不再经端口包装） */
    @Resource
    private AgentRuntimeProperties runtimeProperties;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;

    @PostConstruct
    void startHeartbeat() {
        if (started.compareAndSet(false, true)) {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            Duration interval = heartbeatInterval();
            heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 心跳周期 = 配置 SSE 空闲超时的 1/3（保证连接被容器回收前已多次保活），下限 10s
     * （逻辑平移自原注册表 {@code heartbeatInterval()}，节奏对外逐字不变）。
     */
    Duration heartbeatInterval() {
        long candidate = runtimeProperties.getSseTimeout().toMillis() / 3;
        return candidate > HEARTBEAT_MIN_INTERVAL.toMillis()
                ? Duration.ofMillis(candidate)
                : HEARTBEAT_MIN_INTERVAL;
    }

    @PreDestroy
    void stopHeartbeat() {
        if (started.compareAndSet(true, false) && heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * 单次心跳 tick：遍历会话运行时注册表，对每个上下文的连接句柄下发保活注释帧。
     * <p>保活语义（帧内容 {@code heartbeat}、失败清理）在句柄
     * {@link SseConnectionHandle#heartbeat()} 内，逻辑逐字平移。</p>
     */
    void heartbeat() {
        for (AgentSessionContext context : sessionRegistry.activeContexts()) {
            context.connection().heartbeat();
        }
    }
}

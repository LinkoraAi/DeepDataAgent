package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.domain.event.EventBroadcaster;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionRegistry;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级 SSE 订阅注册表（{@link EventBroadcaster} 进程内实现）。
 * <p>在「注册 / 广播 / 完成」基础上增加：</p>
 * <ul>
 *   <li><b>心跳保活</b>：周期发送 comment 注释行 + {@code extendTimeout}，
 *       避免空闲长连接被容器超时回收（配合前端重连语义）；</li>
 *   <li><b>断连回调</b>：订阅者全部消失（会话最后一个 emitter 清理）时经
 *       {@link SessionRegistry} 定位会话级聚合并触发
 *       {@link AgentSessionContext#interruptActiveRun()}，中断在跑执行解决「断连不中断」缺陷。</li>
 * </ul>
 */
@Component
public class SseEmitterRegistry implements EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** 心跳周期下限（防止 sse-timeout 配置过小时周期被推导为 0） */
    private static final Duration HEARTBEAT_MIN_INTERVAL = Duration.ofSeconds(10);

    private final Map<String, Set<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    @Resource
    private SessionRegistry sessionRegistry;
    @Resource
    private AgentRuntimeProperties properties;
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
     * 心跳周期 = 配置 SSE 空闲超时的 1/3（保证连接被容器回收前已多次保活），下限 10s。
     */
    private Duration heartbeatInterval() {
        long candidate = properties.getSseTimeout().toMillis() / 3;
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
     * 注册会话订阅者，返回受超时 / 断连保护的 emitter。
     */
    public SseEmitter register(String sessionId, Duration timeout) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Set<SseEmitter> emitters = sessionEmitters.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        Runnable cleanup = () -> remove(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * 广播事件至会话的全部订阅者（序列化为 SSE name/data）。
     */
    @Override
    public void broadcast(String sessionId, ChatEvent event) {
        Set<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(ChatEventCodec.toSseEvent(event));
            } catch (Exception ex) {
                remove(sessionId, emitter);
                emitter.completeWithError(ex);
            }
        }
    }

    /**
     * 完成会话的全部订阅者（会话终止 / 执行结束清理）。
     */
    @Override
    public void complete(String sessionId) {
        Set<SseEmitter> emitters = sessionEmitters.remove(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(SseEmitter::complete);
    }

    /**
     * 心跳：对全部存活 emitter 发送注释行保活并探测死连接，失败则清理。
     * <p>Spring WebMvc 7 不再支持 extendTimeout（超时在初始化时固定），
     * 因此连接级超时由 {@link AgentRuntimeProperties#getSseTimeout()} 控制，
     * 此处仅负责客户端侧保活与死连接即时回收。</p>
     */
    private void heartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : sessionEmitters.entrySet()) {
            String sessionId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    remove(sessionId, emitter);
                }
            }
        }
    }

    /**
     * 移除订阅者；若为会话最后一个订阅者，触发在跑执行中断。
     */
    private void remove(String sessionId, SseEmitter emitter) {
        Set<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            sessionEmitters.remove(sessionId, emitters);
            // 订阅者全部消失：经会话级聚合幂等中断在跑 agent，避免僵尸执行继续消耗资源
            sessionRegistry.get(sessionId).ifPresent(AgentSessionContext::interruptActiveRun);
        }
    }
}
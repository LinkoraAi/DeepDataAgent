package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SseKeepAliveScheduler} 保活心跳单测（fix-runtime-layering 1.5）。
 * <p>锁定保活注释帧对外逐字不变的回归契约：帧内容 {@code :heartbeat}（句柄
 * {@code heartbeat()} 下发）、心跳周期 = sseTimeout/3（下限 10s）、
 * 遍历会话运行时注册表（句柄唯一所有者）下发；空闲存活连接心跳后仍保有连接组
 * （二次心跳 / push 均达），NoOp / 已关闭 / 空连接句柄静默跳过。</p>
 */
@ExtendWith(MockitoExtension.class)
class SseKeepAliveSchedulerTest {

    @Mock
    private SessionRuntimeRegistry sessionRegistry;

    /** 运行时全局配置：真实配置对象（心跳周期直读 sseTimeout，不经端口包装） */
    private AgentRuntimeProperties runtimeProperties;

    private SseKeepAliveScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SseKeepAliveScheduler();
        runtimeProperties = new AgentRuntimeProperties();
        ReflectionTestUtils.setField(scheduler, "runtimeProperties", runtimeProperties);
        ReflectionTestUtils.setField(scheduler, "sessionRegistry", sessionRegistry);
    }

    // ==================== 保活帧内容与连接保有（对外逐字不变） ====================

    @Test
    void should_sendKeepAliveComment_when_heartbeat_given_idleConnectionAlive() throws IOException {
        // given（空闲存活连接：真实句柄挂一个 mock emitter，经会话上下文绑定后被调度器遍历）
        SseEmitter emitter = mock(SseEmitter.class);
        SseConnectionHandle handle = liveHandleWith(emitter);

        // when（第一次心跳）
        scheduler.heartbeat();

        // then（下发内容为 :heartbeat 注释帧——句柄保活契约逐字不变）
        ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frames.capture());
        assertTrue(wireOf(frames.getValue()).contains(":heartbeat"));

        // when（第二次心跳：连接未被误摘除，仍可达）
        scheduler.heartbeat();

        // then（同连接再次收到保活帧：连接数不减）
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        // 心跳不影响 buffered 事件推送（连接组仍在）
        handle.push(sampleEvent());
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_skipHeartbeat_when_heartbeat_given_closedOrEmptyHandle() throws IOException {
        // given（三类句柄混合遍历：NoOp 缺省句柄 / 已关闭句柄 / 空连接句柄）
        AgentSessionContext noopContext = new AgentSessionContext(
                AgentSession.create("1", "agent-a", "1.0.0", "{}", null)); // 缺省即 NoOp 句柄
        SseEmitter closedEmitter = mock(SseEmitter.class);
        SseConnectionHandle closedHandle = new SseConnectionHandle();
        closedHandle.addConnection(closedEmitter, Set.of(), 50L);
        closedHandle.close();
        AgentSessionContext closedContext = newContext(closedHandle);
        AgentSessionContext emptyContext = newContext(new SseConnectionHandle()); // 句柄存活但无连接
        when(sessionRegistry.activeContexts()).thenReturn(List.of(noopContext, closedContext, emptyContext));

        // when（心跳不抛异常）
        assertDoesNotThrow(() -> scheduler.heartbeat());

        // then（已关闭句柄不再向连接下发任何帧）
        verify(closedEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ==================== 心跳节奏（sseTimeout/3，下限 10s） ====================

    @Test
    void should_useThirdOfSseTimeout_when_heartbeatInterval_given_longTimeout() {
        // given（30min 空闲超时）
        runtimeProperties.setSseTimeout(Duration.ofMinutes(30));

        // when / then（节奏 = 超时的 1/3，平移自原注册表 heartbeatInterval）
        assertEquals(Duration.ofMinutes(10), scheduler.heartbeatInterval());
    }

    @Test
    void should_floorToTenSeconds_when_heartbeatInterval_given_shortTimeout() {
        // given（15s 超时：1/3 为 5s，低于下限）
        runtimeProperties.setSseTimeout(Duration.ofSeconds(15));

        // when / then（周期下限 10s 兜底，防高频空转）
        assertEquals(Duration.ofSeconds(10), scheduler.heartbeatInterval());
    }

    // ==================== 生命周期 ====================

    @Test
    void should_startAndStopIdempotently_when_lifecycle_given_repeatedCalls() {
        // given（30min 超时 → 首个 tick 在 10min 后，测试窗口内不会触发心跳遍历）
        runtimeProperties.setSseTimeout(Duration.ofMinutes(30));

        // when // then（重复启动仅一个调度线程；重复停止幂等）
        assertDoesNotThrow(() -> {
            scheduler.startHeartbeat();
            scheduler.startHeartbeat();
            scheduler.stopHeartbeat();
            scheduler.stopHeartbeat();
        });
    }

    // ==================== 装配工具 ====================

    /** 真实句柄挂载 mock 连接（仅 buffered、缺省刷写间隔）并绑定到会话上下文。 */
    private SseConnectionHandle liveHandleWith(SseEmitter emitter) {
        SseConnectionHandle handle = new SseConnectionHandle();
        handle.addConnection(emitter, Set.of(), 50L);
        AgentSessionContext context = newContext(handle);
        when(sessionRegistry.activeContexts()).thenReturn(List.of(context));
        return handle;
    }

    private AgentSessionContext newContext(SseConnectionHandle handle) {
        AgentSessionContext context = new AgentSessionContext(
                AgentSession.create("1", "agent-a", "1.0.0", "{}", null));
        context.bindConnection(handle);
        return context;
    }

    /** 展开 SseEventBuilder 为线上字节序字符串（注释帧断言用）。 */
    private String wireOf(SseEmitter.SseEventBuilder builder) {
        Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
        return parts.stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
    }

    private ChatEvent sampleEvent() {
        return ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{\"text\":\"你好\"}", 1L);
    }
}

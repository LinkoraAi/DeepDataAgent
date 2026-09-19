package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SseTransportFactory} 传输工厂单测（原 {@code SseEmitterRegistry} 收敛为纯工厂，D18 句柄单主）。
 * <p>锁定：不再自持 sessionId→handle 映射，仅做「复用判定 / 重建 / 连接注册（携带协商）/
 * 协议帧下发」——存活 SSE 句柄复用、NoOp 与已关闭句柄重建、已关闭句柄拒绝加连、
 * 非 SSE 句柄拒绝注册、注释帧与事件 / 流帧编码下发。</p>
 */
@ExtendWith(MockitoExtension.class)
class SseTransportFactoryTest {

    /** 运行时全局配置：真实配置对象（SSE 超时直读，不经端口包装） */
    private AgentRuntimeProperties runtimeProperties;

    private SseTransportFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SseTransportFactory();
        runtimeProperties = new AgentRuntimeProperties();
        ReflectionTestUtils.setField(factory, "runtimeProperties", runtimeProperties);
    }

    // ==================== 句柄获取（复用判定） ====================

    @Test
    void should_reuseLiveHandle_when_acquireHandle_given_openSseHandle() {
        // given（会话运行时已绑定存活 SSE 句柄）
        SseConnectionHandle live = new SseConnectionHandle();

        // when
        ConnectionHandle acquired = factory.acquireHandle(live);

        // then（同会话多订阅者 fan-out 复用同一句柄）
        assertSame(live, acquired);
    }

    @Test
    void should_createFreshHandle_when_acquireHandle_given_noOpOrClosedHandle() {
        // given（缺省 NoOp 句柄 / 已关闭句柄均不可复用）
        SseConnectionHandle closed = new SseConnectionHandle();
        closed.close();

        // when
        ConnectionHandle fromNoOp = factory.acquireHandle(NoOpConnectionHandle.INSTANCE);
        ConnectionHandle fromClosed = factory.acquireHandle(closed);

        // then（各自重建新句柄，且不复用已关闭句柄）
        assertTrue(fromNoOp instanceof SseConnectionHandle);
        assertTrue(fromClosed instanceof SseConnectionHandle);
        assertNotSame(closed, fromClosed);
    }

    // ==================== 连接注册（携带增量协商） ====================

    @Test
    void should_registerNegotiatedConnection_when_openConnection_given_liveHandle() {
        // given
        SseConnectionHandle handle = new SseConnectionHandle();
        runtimeProperties.setSseTimeout(Duration.ofMinutes(30));

        // when（本连接协商 agent.message 增量、30ms 刷写）
        SseEmitter emitter = factory.openConnection(handle, Set.of(ChatEventType.AGENT_MESSAGE), 30L);

        // then（返回 emitter 已入连接组：刷写间隔取自本连接协商值）
        assertNotNull(emitter);
        assertEquals(30L, handle.deltaFlushIntervalMs());
    }

    @Test
    void should_throwIllegalState_when_openConnection_given_closedHandle() {
        // given（句柄已关闭——绑定竞争窗口）
        SseConnectionHandle handle = new SseConnectionHandle();
        handle.close();
        runtimeProperties.setSseTimeout(Duration.ofMinutes(30));

        // when & then（拒绝向已关闭句柄添加新连接）
        assertThrows(IllegalStateException.class,
                () -> factory.openConnection(handle, Set.of(), 50L));
    }

    @Test
    void should_throwIllegalArgument_when_openConnection_given_nonSseHandle() {
        // when & then（非 SSE 句柄不属本工厂职责，快速失败）
        assertThrows(IllegalArgumentException.class, () -> factory
                .openConnection(mock(ConnectionHandle.class), Set.of(), 50L));
    }

    // ==================== 协议帧下发 ====================

    @Test
    void should_sendCommentFrame_when_sendComment_given_mockEmitter() throws IOException {
        // given
        SseEmitter emitter = mock(SseEmitter.class);

        // when
        factory.sendComment(emitter, "connected");

        // then（注释帧内容逐字为 : connected）
        ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frames.capture());
        assertTrue(wireOf(frames.getValue()).contains(":connected"));
    }

    @Test
    void should_sendEncodedEventAndFrame_when_sendEventAndFrame_given_mockEmitter() throws IOException {
        // given
        SseEmitter emitter = mock(SseEmitter.class);
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE,
                "{\"text\":\"你好\"}", 1L);
        StreamFrame frame = new StreamFrame(ChatEventType.EVENT_DELTA, "evt_1",
                ChatEventType.AGENT_MESSAGE, "{\"text\":\"你\"}");

        // when
        factory.sendEvent(emitter, event);
        factory.sendFrame(emitter, frame);

        // then（领域事件经信封编码、流帧经帧编码各下发一次）
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 展开 SseEventBuilder 为线上字节序字符串（注释帧断言用）。 */
    private String wireOf(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
    }
}

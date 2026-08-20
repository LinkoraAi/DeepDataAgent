package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.assembler.SseEventEnvelopeAssembler;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SseConnectionHandle} 连接层适配单测：多订阅者 fan-out、排除推送、
 * 全断触发 onDisconnect、close 关闭全部订阅者与协议转换（领域事件 → 信封 → SSE）。
 */
class SseConnectionHandleTest {

    private SseEventEnvelopeAssembler envelopeAssembler;
    private SseConnectionHandle handle;

    @BeforeEach
    void setUp() {
        envelopeAssembler = new SseEventEnvelopeAssembler();
        ReflectionTestUtils.setField(envelopeAssembler, "objectMapper", new ObjectMapper());
        handle = new SseConnectionHandle(envelopeAssembler);
    }

    @Test
    void should_fanOutToAllConnections_when_push_given_multipleEmitters() throws IOException {
        // given（同会话两个订阅者）
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1);
        handle.addConnection(e2);

        // when
        handle.push(sampleEvent());

        // then（领域事件经信封装配后广播到全部活跃连接）
        verify(e1).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
        assertTrue(handle.isActive());
        assertEquals(2, handle.activeCount());
    }

    @Test
    void should_excludeSpecifiedConnection_when_pushExcluding_given_emitterInExcludedSet() throws IOException {
        // given
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1);
        handle.addConnection(e2);

        // when（排除 e1，防乱序补发场景）
        handle.pushExcluding(sampleEvent(), Set.of(e1));

        // then
        verify(e1, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_triggerOnDisconnect_when_removeConnection_given_lastEmitter() {
        // given（注册断连回调 + 一个连接）
        Runnable handler = mock(Runnable.class);
        handle.onDisconnect(handler);
        SseEmitter e1 = mock(SseEmitter.class);
        handle.addConnection(e1);

        // when（移除最后一个连接）
        handle.removeConnection(e1);

        // then（全断触发回调）
        verify(handler).run();
        assertFalse(handle.isActive());
    }

    @Test
    void should_notTriggerOnDisconnect_when_removeConnection_given_remainingEmitters() {
        // given（两个连接仅移除一个）
        Runnable handler = mock(Runnable.class);
        handle.onDisconnect(handler);
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1);
        handle.addConnection(e2);

        // when
        handle.removeConnection(e1);

        // then（仍有存活连接，不触发断连回调）
        verify(handler, never()).run();
        assertTrue(handle.isActive());
    }

    @Test
    void should_completeAll_when_close_given_multipleEmitters() {
        // given
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1);
        handle.addConnection(e2);

        // when（会话终止 / 句柄替换触发 close）
        handle.close();

        // then（完成全部订阅者且句柄置关闭）
        verify(e1).complete();
        verify(e2).complete();
        assertTrue(handle.isClosed());
        assertFalse(handle.isActive());
    }

    @Test
    void should_rejectAddConnection_when_addConnection_given_closed() {
        // given
        handle.close();

        // when & then
        assertThrows(IllegalStateException.class, () -> handle.addConnection(mock(SseEmitter.class)));
    }

    @Test
    void should_doNothing_when_push_given_noConnection() {
        // when & then（无连接或已关闭时 push 为空操作，不抛异常）
        handle.push(sampleEvent());
        assertFalse(handle.isActive());
    }

    private ChatEvent sampleEvent() {
        return ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE, "{\"content\":[]}", 1L);
    }
}
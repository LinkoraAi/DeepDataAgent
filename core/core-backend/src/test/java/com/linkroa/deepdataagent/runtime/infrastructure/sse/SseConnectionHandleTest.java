package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SseConnectionHandle} 连接层适配单测：多订阅者 fan-out、排除推送、
 * 断连不取消（onDisconnect 空操作）、close 关闭全部订阅者与协议转换（领域事件 → 信封 → SSE）。
 */
class SseConnectionHandleTest {

    private SseConnectionHandle handle;

    @BeforeEach
    void setUp() {
        handle = new SseConnectionHandle();
    }

    @Test
    void should_fanOutToAllConnections_when_push_given_multipleEmitters() throws IOException {
        // given（同会话两个订阅者，默认仅 buffered）
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when
        handle.push(sampleEvent());

        // then（领域事件经信封装配后广播到全部活跃连接）
        verify(e1).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_excludeSpecifiedConnection_when_pushExcluding_given_emitterInExcludedSet() throws IOException {
        // given
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when（排除 e1，防乱序补发场景）
        handle.pushExcluding(sampleEvent(), Set.of(e1));

        // then
        verify(e1, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_notTriggerDisconnectSideEffect_when_removeConnection_given_lastEmitter() throws IOException {
        // given（注册断连回调 + 一个连接）
        Runnable cancelSideEffect = mock(Runnable.class);
        handle.onDisconnect(cancelSideEffect);
        SseEmitter e1 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(), 50L);

        // when（移除最后一个连接）
        handle.removeConnection(e1);

        // then（断连不取消：onDisconnect 为空操作，回调被忽略，不触发任何执行副作用）
        verify(cancelSideEffect, never()).run();
        handle.push(sampleEvent());
        verify(e1, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_notTriggerOnDisconnect_when_removeConnection_given_remainingEmitters() {
        // given（两个连接仅移除一个）
        Runnable handler = mock(Runnable.class);
        handle.onDisconnect(handler);
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when
        handle.removeConnection(e1);

        // then（仍有存活连接，不触发断连回调）
        verify(handler, never()).run();
    }

    @Test
    void should_completeAll_when_close_given_multipleEmitters() {
        // given
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when（会话终止 / 句柄替换触发 close）
        handle.close();

        // then（完成全部订阅者且句柄置关闭）
        verify(e1).complete();
        verify(e2).complete();
        assertTrue(handle.isClosed());
    }

    @Test
    void should_rejectAddConnection_when_addConnection_given_closed() {
        // given
        handle.close();

        // when & then（携带协商参数的 addConnection 在关闭后仍拒绝新增连接）
        assertThrows(IllegalStateException.class,
                () -> handle.addConnection(mock(SseEmitter.class), Set.of(), 50L));
    }

    @Test
    void should_doNothing_when_push_given_noConnection() {
        // when & then（无连接或已关闭时 push 为空操作，不抛异常）
        handle.push(sampleEvent());
    }

    // ==================== 连接级增量协商（event_deltas[]） ====================

    @Test
    void should_onlySendFramesToNegotiatedConnection_when_pushFrame_given_mixedNegotiation() throws IOException {
        // given（e1 协商 agent.message 增量；e2 默认仅 buffered）
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(ChatEventType.AGENT_MESSAGE), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when
        handle.pushFrame(streamFrame(ChatEventType.EVENT_DELTA, ChatEventType.AGENT_MESSAGE));

        // then（协商仅作用于当前连接，不影响其他连接）
        verify(e1).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_notSendFrame_when_pushFrame_given_targetNotNegotiated() throws IOException {
        // given（仅协商 agent.thinking，推 agent.message 帧）
        SseEmitter e1 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(ChatEventType.AGENT_THINKING), 50L);

        // when
        handle.pushFrame(streamFrame(ChatEventType.EVENT_START, ChatEventType.AGENT_MESSAGE));

        // then（目标类型不匹配不下发）
        verify(e1, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_bufferedOnlyConnectionsStillReceive_when_push_given_negotiatedFrameConnections() throws IOException {
        // given（协商连接与默认连接混合）
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        handle.addConnection(e1, Set.of(ChatEventType.AGENT_MESSAGE), 50L);
        handle.addConnection(e2, Set.of(), 50L);

        // when（buffered 完整事件全量广播，不受协商影响）
        handle.push(sampleEvent());

        // then
        verify(e1).send(any(SseEmitter.SseEventBuilder.class));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void should_returnMinIntervalOfDeltaSubscribers_when_deltaFlushIntervalMs_given_multipleConnections() {
        // given & then（无增量订阅者：缺省 50ms）
        assertEquals(50L, handle.deltaFlushIntervalMs());

        // when（一个协商连接 100ms）
        handle.addConnection(mock(SseEmitter.class), Set.of(ChatEventType.AGENT_MESSAGE), 100L);

        // then
        assertEquals(100L, handle.deltaFlushIntervalMs());

        // when（另一协商连接 30ms + 未协商连接 10ms（不参与最短间隔计算））
        handle.addConnection(mock(SseEmitter.class), Set.of(ChatEventType.AGENT_THINKING), 30L);
        handle.addConnection(mock(SseEmitter.class), Set.of(), 10L);

        // then（取增量订阅者最短间隔）
        assertEquals(30L, handle.deltaFlushIntervalMs());
    }

    private ChatEvent sampleEvent() {
        return ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{\"text\":\"你好\"}", 1L);
    }

    private StreamFrame streamFrame(ChatEventType frameType, ChatEventType targetType) {
        return new StreamFrame(frameType, "evt_1", targetType, "{\"event_id\":\"evt_1\"}");
    }
}

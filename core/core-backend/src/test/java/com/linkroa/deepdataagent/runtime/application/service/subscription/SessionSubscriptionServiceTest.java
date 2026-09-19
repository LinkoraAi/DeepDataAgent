package com.linkroa.deepdataagent.runtime.application.service.subscription;

import com.linkroa.deepdataagent.runtime.application.port.SseTransportPort;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeQueryService;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TextBlockAccumulator.InFlightStream;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.SseConnectionHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionSubscriptionService} SSE 订阅编排下沉单测（fix-runtime-layering 1.4）。
 * <p>锁定下沉后的关键不变量：<b>先绑定后回放</b>全序（绑定 / 连接注册先于
 * {@code : connected} 握手与历史回放）、三段重连判定（一段回补 / 二段只接新增 /
 * 三段只回放）、多订阅者 fan-out 共享句柄、断连不取消、增量协商解析（非法取值必须 400）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionSubscriptionServiceTest {

    @Mock
    private AgentRuntimeQueryService queryService;
    @Mock
    private SessionRuntimeRegistry sessionRegistry;
    @Mock
    private SseTransportPort transportPort;

    private AgentSession session;
    private AgentSessionContext context;
    private SessionSubscriptionService service;

    @BeforeEach
    void setUp() {
        session = AgentSession.create("1", "agent-a", "1.0.0", "{}", null);
        context = spy(new AgentSessionContext(session));
        service = new SessionSubscriptionService();
        ReflectionTestUtils.setField(service, "queryService", queryService);
        ReflectionTestUtils.setField(service, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(service, "sseTransportPort", transportPort);
    }

    // ==================== 先绑定后回放（防丢失窗口全序） ====================

    @Test
    void should_bindBeforeReplayInOrder_when_open_given_historyEvents() throws IOException {
        // given（游标 3、两条历史事件；无增量协商）
        ConnectionHandle handle = mock(ConnectionHandle.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenReturn(handle);
        when(transportPort.openConnection(eq(handle), any(), eq(50L))).thenReturn(emitter);
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(session.sessionId(), "3"))
                .thenReturn(new AgentRuntimeQueryService.ReplayPosition(3L, false));
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of(
                bufferedEvent(4L), bufferedEvent(5L)));
        // 未协商增量（eventDeltas=null → 目标集空）：一段回补入口短路，不查进行中流

        // when
        SseEmitter opened = service.open(session.sessionId(), "3", null, null);

        // then（全序：取句柄（绑定前为 NoOp）→ 绑定 → 注册连接 → connected 握手 → 解析游标 → 回放）
        assertSame(emitter, opened);
        InOrder order = inOrder(transportPort, queryService, context);
        order.verify(transportPort).acquireHandle(NoOpConnectionHandle.INSTANCE);
        order.verify(context).bindConnection(handle);
        order.verify(transportPort).openConnection(handle, Set.of(), 50L);
        order.verify(transportPort).sendComment(emitter, "connected");
        order.verify(queryService).resolveReplayPosition(session.sessionId(), "3");
        order.verify(queryService).replayEvents(any(ReplayQuery.class));
        order.verify(transportPort, times(2)).sendEvent(eq(emitter), any());
        // 断连不取消：绑定过程不得接线任何取消 / 中断回调
        verify(handle, never()).onDisconnect(any());
    }

    // ==================== 三段重连判定 ====================

    @Test
    void should_backfillStartAndAggregatedDelta_when_open_given_cursorBeforeInFlightStart() throws IOException {
        // given（一段：游标 2 早于进行中流 baseSeq 3，agent.message 已协商且有累积文本）
        givenNegotiatedSubscription(new AgentRuntimeQueryService.ReplayPosition(2L, false),
                new InFlightStream("evt_9", ChatEventType.AGENT_MESSAGE, "blk-1", 3L), "已生成文本");

        // when
        service.open(session.sessionId(), "evt_2", List.of("agent.message"), 30L);

        // then（回补 event_start + 聚合 event_delta，共两帧）
        ArgumentCaptor<StreamFrame> frames = ArgumentCaptor.forClass(StreamFrame.class);
        verify(transportPort, times(2)).sendFrame(any(SseEmitter.class), frames.capture());
        StreamFrame start = frames.getAllValues().get(0);
        assertEquals(ChatEventType.EVENT_START, start.frameType());
        assertEquals("evt_9", start.eventId());
        assertEquals(ChatEventType.AGENT_MESSAGE, start.targetType());
        StreamFrame delta = frames.getAllValues().get(1);
        assertEquals(ChatEventType.EVENT_DELTA, delta.frameType());
        assertEquals("evt_9", delta.eventId());
        assertTrue(delta.payloadJson().contains("已生成文本"));
        // 协商参数下传：目标类型与刷写间隔
        verify(transportPort).openConnection(any(), eq(Set.of(ChatEventType.AGENT_MESSAGE)), eq(30L));
    }

    @Test
    void should_onlyReplayHistoryFromCursor_when_open_given_midStreamCursor() throws IOException {
        // given（二段：游标等于进行中事件 id → 不重放历史 delta、仅接重连后新增）
        givenNegotiatedSubscription(new AgentRuntimeQueryService.ReplayPosition(9L, true), null, null);

        // when
        service.open(session.sessionId(), "evt_9", List.of("agent.message"), null);

        // then（回放起点=9 且完全不再下发流帧）
        ArgumentCaptor<ReplayQuery> replayed = ArgumentCaptor.forClass(ReplayQuery.class);
        verify(queryService).replayEvents(replayed.capture());
        assertEquals(9L, replayed.getValue().afterSequenceNum());
        verify(transportPort, never()).sendFrame(any(), any());
    }

    @Test
    void should_notBackfill_when_open_given_cursorAfterInFlightBase() throws IOException {
        // given（守卫：游标 5 晚于进行中流 baseSeq 3 → 不回补过期现场）
        givenNegotiatedSubscription(new AgentRuntimeQueryService.ReplayPosition(5L, false),
                new InFlightStream("evt_9", ChatEventType.AGENT_MESSAGE, "blk-1", 3L), "");

        // when
        service.open(session.sessionId(), "evt_2", List.of("agent.message"), null);

        // then
        verify(transportPort, never()).sendFrame(any(), any());
    }

    @Test
    void should_replayOnlyBuffered_when_open_given_inFlightStreamCleared() throws IOException {
        // given（三段：生成已完成、进行中流已清空 → 天然不再重放 delta）
        givenNegotiatedSubscription(new AgentRuntimeQueryService.ReplayPosition(1L, false), null, null);

        // when
        service.open(session.sessionId(), "evt_1", List.of("agent.thinking"), null);

        // then
        verify(transportPort, never()).sendFrame(any(), any());
    }

    // ==================== 增量协商解析 ====================

    @Test
    void should_throwIllegalArgument_when_open_given_illegalDeltaNegotiation() {
        // given（未知 / 不可协商的增量取值 MUST 400，不再静默忽略）
        when(queryService.getSession(session.sessionId())).thenReturn(session);

        // when & then（解析期即抛参错，早于任何连接绑定）
        assertThrows(IllegalArgumentException.class,
                () -> service.open(session.sessionId(), null, List.of("bogus.type"), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.open(session.sessionId(), null, List.of("agent.tool_use"), null));
        // 非法协商在触碰注册表 / 连接层之前失败
        verify(sessionRegistry, never()).getOrCreate(any());
        verify(transportPort, never()).acquireHandle(any());
    }

    @Test
    void should_bufferedOnlyWithDefaultInterval_when_open_given_nonPositiveFlushInterval() throws IOException {
        // given（无增量协商 + 非正刷写间隔）
        ConnectionHandle handle = mock(ConnectionHandle.class);
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenReturn(handle);
        when(transportPort.openConnection(eq(handle), any(), any(Long.class))).thenReturn(mock(SseEmitter.class));
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(any(), any()))
                .thenReturn(new AgentRuntimeQueryService.ReplayPosition(0L, false));
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of());

        // when
        service.open(session.sessionId(), null, null, -5L);

        // then（目标集为空 + 刷写间隔收敛缺省 50ms）
        verify(transportPort).openConnection(eq(handle), eq(Set.of()), eq(50L));
        verify(transportPort, never()).sendFrame(any(), any());
    }

    // ==================== 句柄单主 fan-out 与断连回调 ====================

    @Test
    void should_reuseLiveHandleForSecondSubscriber_when_open_given_sameSessionFanOut() throws IOException {
        // given（模拟句柄唯一所有者语义：存活句柄复用、关闭重建——与工厂实现同一契约）
        AtomicReference<SseConnectionHandle> owned = new AtomicReference<>();
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenAnswer(invocation -> {
            ConnectionHandle bound = invocation.getArgument(0);
            if (bound instanceof SseConnectionHandle live && !live.isClosed()) {
                return live;
            }
            SseConnectionHandle created = new SseConnectionHandle();
            owned.set(created);
            return created;
        });
        when(transportPort.openConnection(any(), any(), any(Long.class))).thenReturn(mock(SseEmitter.class));
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(any(), any()))
                .thenReturn(new AgentRuntimeQueryService.ReplayPosition(0L, false));
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of());

        // when（两个标签页先后订阅同一会话）
        service.open(session.sessionId(), null, null, null);
        service.open(session.sessionId(), null, null, null);

        // then（共享同一句柄、上下文绑定的句柄不变）
        ArgumentCaptor<ConnectionHandle> handles = ArgumentCaptor.forClass(ConnectionHandle.class);
        verify(transportPort, times(2)).openConnection(handles.capture(), any(), any(Long.class));
        assertSame(handles.getAllValues().get(0), handles.getAllValues().get(1));
        assertSame(owned.get(), context.connection());
    }

    @Test
    void should_notWireInterruptCallback_when_open_given_boundContext() throws IOException {
        // given（断连不取消：SSE 仅为观察 / 回放通道，连接断开 MUST NOT 触发在跑执行取消）
        ConnectionHandle handle = mock(ConnectionHandle.class);
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenReturn(handle);
        when(transportPort.openConnection(any(), any(), any(Long.class))).thenReturn(mock(SseEmitter.class));
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(any(), any()))
                .thenReturn(new AgentRuntimeQueryService.ReplayPosition(0L, false));
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of());

        // when（打开订阅并注册连接）
        service.open(session.sessionId(), null, null, null);

        // then（不接线任何断连回调，会话运行时不被中断）
        verify(handle, never()).onDisconnect(any());
        verify(context, never()).interruptCurrentRun();
    }

    @Test
    void should_completeWithError_when_open_given_sendFailure() throws IOException {
        // given（握手后回放发送抛 IO 异常）
        ConnectionHandle handle = mock(ConnectionHandle.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenReturn(handle);
        when(transportPort.openConnection(any(), any(), any(Long.class))).thenReturn(emitter);
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(any(), any()))
                .thenReturn(new AgentRuntimeQueryService.ReplayPosition(0L, false));
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of(bufferedEvent(1L)));
        org.mockito.Mockito.doThrow(new IOException("broken pipe"))
                .when(transportPort).sendEvent(any(), any());

        // when（异常不外抛，以错误完成连接——行为对齐原控制器）
        SseEmitter opened = service.open(session.sessionId(), null, null, null);

        // then
        assertSame(emitter, opened);
        verify(emitter).completeWithError(any(IOException.class));
    }

    // ==================== 装配工具 ====================

    /** 装配「已协商增量 + 指定游标 / 进行中流 / 累积文本」的订阅现场（非二段才提供进行中流）。 */
    private void givenNegotiatedSubscription(AgentRuntimeQueryService.ReplayPosition position,
                                             InFlightStream inFlight, String accumulated) throws IOException {
        when(sessionRegistry.getOrCreate(session)).thenReturn(context);
        when(transportPort.acquireHandle(any())).thenReturn(mock(ConnectionHandle.class));
        when(transportPort.openConnection(any(), any(), any(Long.class))).thenReturn(mock(SseEmitter.class));
        when(queryService.getSession(session.sessionId())).thenReturn(session);
        when(queryService.resolveReplayPosition(any(), any())).thenReturn(position);
        when(queryService.replayEvents(any(ReplayQuery.class))).thenReturn(List.of());
        if (!position.midStream()) {
            when(queryService.currentInFlightStream(session.sessionId())).thenReturn(inFlight);
        }
        if (accumulated != null && !accumulated.isEmpty()) {
            when(queryService.accumulatedStreamText(session.sessionId(), "blk-1")).thenReturn(accumulated);
        }
    }

    private ChatEvent bufferedEvent(long seq) {
        return ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MESSAGE, "{\"text\":\"m\"}", seq);
    }
}

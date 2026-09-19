package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.application.service.execution.ExecutionContext;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TurnEventWriter} 直测（decompose-command-facade 2.3）：
 * 钉死自门面纯搬移的三条红线语义——<b>先推后入队</b>（SSE 不被落库 I/O 阻塞）、
 * <b>推送失败吞异常仅记 WARN</b>（不向编排层上抛）、<b>seq 会话级计数器分配 / 冷启动回落 DB max</b>；
 * 另覆盖流式帧（不落库、不消耗 seq）与 delta 缓冲排空的静默分支。
 * <p>编排侧端到端钉桩（哪些事件按何序提交）随门面拆分迁至
 * {@code execution.TurnExecutionServiceTest} 与 {@code execution.TurnFinalizerTest}，以真实 Writer + 替身端口钉桩。</p>
 */
@ExtendWith(MockitoExtension.class)
class TurnEventWriterTest {

    @Mock private SessionRuntimeRegistry sessionRegistry;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private ChatEventPersister chatEventPersister;
    @Mock private AgentSessionContext sessionContext;
    @Mock private AgentSession session;
    @Mock private ConnectionHandle connectionHandle;
    @Mock private TurnRunState runState;

    private TurnEventWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TurnEventWriter();
        ReflectionTestUtils.setField(writer, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(writer, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(writer, "chatEventPersister", chatEventPersister);
    }

    /** 连接句柄绑定（推送 / 帧下发的唯一出口）。 */
    private void bindConnection() {
        when(sessionContext.connection()).thenReturn(connectionHandle);
    }

    /** 会话语境桩（组装持久化事件所需：身份镜像 + 会话级计数器）。 */
    private void wireSessionContext(long seq) {
        when(sessionContext.session()).thenReturn(session);
        when(session.sessionId()).thenReturn("sess_1");
        when(sessionContext.nextSequence()).thenReturn(seq);
        bindConnection();
    }

    @Test
    void should_pushThenEnqueue_when_persistAndBroadcast_given_assembledEvent() {
        // given（红线：先经连接层推送、再入异步批量队列落库，事件同一条）
        wireSessionContext(7L);
        ExecutionContext context = new ExecutionContext(sessionContext, runState, "sthr_main");
        AssembledEvent assembled = ChatEventFactory.INSTANCE.sessionStatus(AgentSessionStatus.IDLE, null);

        // when
        writer.persistAndBroadcast(context, assembled);

        // then（推送先于入队；seq 取自会话级计数器，线程归属随现场传递）
        ArgumentCaptor<ChatEvent> pushed = ArgumentCaptor.forClass(ChatEvent.class);
        ArgumentCaptor<ChatEvent> enqueued = ArgumentCaptor.forClass(ChatEvent.class);
        InOrder order = inOrder(connectionHandle, chatEventPersister);
        order.verify(connectionHandle).push(pushed.capture());
        order.verify(chatEventPersister).enqueue(enqueued.capture());
        assertEquals(pushed.getValue(), enqueued.getValue());
        assertEquals(7L, pushed.getValue().seq());
        assertEquals("sess_1", pushed.getValue().sessionId());
        assertEquals("sthr_main", pushed.getValue().sessionThreadId());
    }

    @Test
    void should_reuseFrameEventId_when_persistAndBroadcast_given_explicitEventId() {
        // given（流式块最终事件复用帧的 evt_ ID，回放 + 实时重合窗口可关联去重）
        wireSessionContext(9L);
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);
        AssembledEvent assembled = ChatEventFactory.INSTANCE.agentMessage("最终文本");

        // when
        writer.persistAndBroadcast(context, assembled, "evt_shared_1");

        // then
        ArgumentCaptor<ChatEvent> captor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(chatEventPersister).enqueue(captor.capture());
        assertEquals("evt_shared_1", captor.getValue().eventId());
        assertEquals(9L, captor.getValue().seq());
    }

    @Test
    void should_generateEventId_when_assemble_given_eventIdAbsent() {
        // given（eventId 缺失时自动生成 evt_ 前缀；纯组装不触碰连接层）
        when(sessionContext.session()).thenReturn(session);
        when(session.sessionId()).thenReturn("sess_1");
        when(sessionContext.nextSequence()).thenReturn(1L);
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);
        AssembledEvent assembled = ChatEventFactory.INSTANCE.sessionStatus(AgentSessionStatus.IDLE, null);

        // when
        ChatEvent event = writer.assemble(context, assembled);

        // then
        assertNotNull(event.eventId());
        assertTrue(event.eventId().startsWith("evt_"));
        verifyNoInteractions(connectionHandle);
    }

    @Test
    void should_pushFrameWithoutPersist_when_pushFrame_given_frame() {
        // given（红线：帧不落库、不消耗 seq、不入回放，仅经连接层实时下发）
        bindConnection();
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);
        AssembledEvent frame = ChatEventFactory.INSTANCE.eventDelta("evt_1", "增量");

        // when
        writer.pushFrame(context, frame, "evt_1", ChatEventType.AGENT_MESSAGE);

        // then（帧信封携带类型 / 关联事件 ID / 协商目标类型；零落库零入队）
        ArgumentCaptor<StreamFrame> captor = ArgumentCaptor.forClass(StreamFrame.class);
        verify(connectionHandle).pushFrame(captor.capture());
        assertEquals(frame.type(), captor.getValue().frameType());
        assertEquals("evt_1", captor.getValue().eventId());
        assertEquals(ChatEventType.AGENT_MESSAGE, captor.getValue().targetType());
        verifyNoInteractions(chatEventPersister, chatEventRepository);
        verify(sessionContext, never()).nextSequence();
    }

    @Test
    void should_swallowFailureQuietly_when_pushFrame_given_connectionThrows() {
        // given（红线：吞推送异常仅记 WARN，不向编排层上抛）
        bindConnection();
        when(sessionContext.sessionId()).thenReturn("sess_1");
        doThrow(new RuntimeException("连接已断开")).when(connectionHandle).pushFrame(any(StreamFrame.class));
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);
        AssembledEvent frame = ChatEventFactory.INSTANCE.eventDelta("evt_1", "增量");

        // when / then（不抛异常即收口）
        assertDoesNotThrow(() -> writer.pushFrame(context, frame, "evt_1", ChatEventType.AGENT_MESSAGE));
    }

    @Test
    void should_flushPendingDeltaAsEventDelta_when_flushTextDelta_given_nonEmptyBuffer() {
        // given
        bindConnection();
        when(runState.drainPendingDelta()).thenReturn("部分文本");
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);

        // when
        writer.flushTextDelta(context, runState, "evt_9");

        // then（缓冲内容以 event_delta 帧下发）
        ArgumentCaptor<StreamFrame> captor = ArgumentCaptor.forClass(StreamFrame.class);
        verify(connectionHandle).pushFrame(captor.capture());
        assertEquals("evt_9", captor.getValue().eventId());
        assertTrue(captor.getValue().payloadJson().contains("部分文本"));
    }

    @Test
    void should_pushNothing_when_flushTextDelta_given_emptyBuffer() {
        // given（缓冲为空时静默，不发空帧）
        when(runState.drainPendingDelta()).thenReturn("");
        ExecutionContext context = new ExecutionContext(sessionContext, runState, null);

        // when
        writer.flushTextDelta(context, runState, "evt_9");

        // then
        verify(connectionHandle, never()).pushFrame(any(StreamFrame.class));
    }

    @Test
    void should_swallowFailureQuietly_when_pushQuietly_given_connectionThrows() {
        // given（红线：广播失败吞异常仅 WARN——事件仍会经入队落库、断线重连回放兜底；
        //  告警取事件自身字段，不触碰聚合）
        bindConnection();
        doThrow(new RuntimeException("连接已断开")).when(connectionHandle).push(any(ChatEvent.class));
        ChatEvent event = ChatEvent.create("sess_1", ChatEventType.AGENT_MESSAGE, "{}", 1L);

        // when / then
        assertDoesNotThrow(() -> writer.pushQuietly(sessionContext, event));
    }

    @Test
    void should_skipPush_when_pushQuietly_given_nullEvent() {
        // given（null 事件直接短路，不触碰连接层）
        // when
        writer.pushQuietly(sessionContext, null);

        // then
        verify(sessionContext, never()).connection();
        verifyNoInteractions(connectionHandle);
    }

    @Test
    void should_useSessionCounter_when_nextSequence_given_sessionPresentInRegistry() {
        // given（会话在场：经内存计数器分配，不触 DB）
        when(sessionRegistry.get("sess_1")).thenReturn(Optional.of(sessionContext));
        when(sessionContext.nextSequence()).thenReturn(42L);

        // when
        long seq = writer.nextSequence("sess_1");

        // then
        assertEquals(42L, seq);
        verifyNoInteractions(chatEventRepository);
    }

    @Test
    void should_fallbackToDbMax_when_nextSequence_given_sessionAbsentFromRegistry() {
        // given（冷启动 / 本次进程从未运行：回落 DB max(seq)+1）
        when(sessionRegistry.get("sess_1")).thenReturn(Optional.empty());
        when(chatEventRepository.nextSequenceNum("sess_1")).thenReturn(5L);

        // when
        long seq = writer.nextSequence("sess_1");

        // then
        assertEquals(5L, seq);
        verify(sessionRegistry, never()).getOrCreate(any());
    }
}

package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeServiceTestSupport;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话时序整合测试（align-cloud-agents-session-platform tasks 3.14）：以真实执行协作网
 * （{@link TurnExecutionService} + {@link TurnEventWriter} + {@link TurnFinalizer} +
 * {@code InMemorySessionRegistry} + {@code SessionLifecycleService}，经
 * {@link AgentRuntimeServiceTestSupport} 装配）横向拉通八条会话时序，逐条断言
 * <b>完整事件类型序列</b>与<b>会话内 seq 顺序</b>（seq 由会话级计数器真实分配，
 * 从 1 起严格 +1——无空洞、无乱序）。
 *
 * <p>八条时序：正常收流 / HITL 挂起 / 中断（含在飞工具配对补偿）/ 执行出错 /
 * 迭代上限 / 归档 / 删除 / 断连不取消。其中「归档零事件」与「断连不取消」此前
 * 无端到端序列断言，由本类补齐。</p>
 */
class SessionTurnTimelineTest extends AgentRuntimeServiceTestSupport {

    /** 会话内 seq 全序断言消息（seq 由会话级计数器分配，从 1 起逐条 +1）。 */
    private static final String SEQ_MONOTONIC_MESSAGE = "会话内 seq 必须从 1 起严格递增";

    // ==================== 断言底座 ====================

    /**
     * 断言完整事件类型序列与 seq 全序：类型逐位相等，且 seq 严格等于「位次 + 1」。
     *
     * @param saved 按落库顺序的事件快照
     * @param types 期望的事件类型序列（完整序列，长度须与落库条数一致）
     */
    private void assertTimeline(List<ChatEvent> saved, ChatEventType... types) {
        assertEquals(List.of(types), saved.stream().map(ChatEvent::type).toList(),
                "事件类型序列不符，实际: " + saved.stream().map(e -> e.type().name()).toList());
        for (int index = 0; index < saved.size(); index++) {
            assertEquals(index + 1L, saved.get(index).seq(), SEQ_MONOTONIC_MESSAGE);
        }
    }

    /** 收场二事件（{@code thread_status_*} 先、{@code status_*} 后）断言：镜像次序 + 同一 stop_reason。 */
    private void assertTerminalTail(List<ChatEvent> saved, ChatEventType threadType, ChatEventType sessionType,
                                    String stopReasonType) {
        assertEquals(threadType, saved.get(saved.size() - 2).type());
        ChatEvent last = saved.get(saved.size() - 1);
        assertEquals(sessionType, last.type());
        assertTrue(last.payload().contains("\"stop_reason\":{\"type\":\"" + stopReasonType + "\"}"),
                sessionType.name() + " 应携带 stop_reason=" + stopReasonType + "，实际: " + last.payload());
    }

    /** 装配一轮执行的公共桩（会话存在 + 组装成功 + 绑定连接句柄），返回本轮 BuiltAgent 替身。 */
    private BuiltAgent wireRound(AgentSession session) {
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        return agent;
    }

    /** 桩执行流信号（统一入口，避免各用例重复书写 streamEvents 匹配串）。 */
    private void stubStream(Flux<AgentStreamSignal> signals) {
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(signals);
    }

    // ==================== 1. 正常收流 ====================

    @Test
    void should_emitFullTimelineInSeqOrder_when_sendMessageAsync_given_normalRound() {
        // given（正常轮：文本块收尾 + AGENT_END 提前终态，onComplete 兜底幂等）
        AgentSession session = idleSession();
        wireRound(session);
        stubStream(Flux.just(
                AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"),
                AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（开跑双状态事件 → 内容事件 → 收场二事件，seq 从 1 起严格递增）
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.AGENT_MESSAGE,
                ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE);
        assertTerminalTail(saved, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE, "end_turn");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }

    // ==================== 2. HITL 挂起 ====================

    @Test
    void should_emitToolUseTimelineWithoutTerminalEvents_when_sendMessageAsync_given_hitlSuspendedBatch() {
        // given（两个工具调用聚合成入参后整批 REQUIRE 挂起：挂起即物理轮终局，等待现场只由账本承载）
        AgentSession session = idleSession();
        wireRound(session);
        stubStream(suspendBatchSignals());

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));

        // then（开跑双状态事件 + 两条 agent.tool_use；无任何终态事件，seq 止于工具调用）
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.AGENT_TOOL_USE,
                ChatEventType.AGENT_TOOL_USE);
        assertTrue(saved.stream()
                        .filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE)
                        .allMatch(e -> e.payload().contains("\"tool_use_id\"")),
                "agent.tool_use 应以 tool_use_id 承载工具调用身份");
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_AWAIT);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.FINISH_TURN));
    }

    // ==================== 3. 中断（含在飞工具配对补偿） ====================

    @Test
    void should_compensateInFlightToolUseWithSyntheticResult_when_sendMessageAsync_given_interruptMidToolCall() {
        // given（工具调用已落库 tool_use 而结果未到，收流前收到取消：中断不得留下悬空 tool_use）
        AgentSession session = idleSession();
        wireRound(session);
        stubStream(Flux.just(
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", "search",
                                "{\"q\":\"x\"}", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null))
                .doOnComplete(() -> sessionRegistry.get(session.sessionId())
                        .ifPresent(AgentSessionContext::interruptCurrentRun)));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "搜索"));

        // then（合成错误结果紧随 tool_use 之后、seq 先于收场二事件）
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.AGENT_TOOL_USE,
                ChatEventType.AGENT_TOOL_RESULT,
                ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE);
        ChatEvent synthetic = saved.get(3);
        assertTrue(synthetic.payload().contains("\"tool_use_id\":\"tc-1\""),
                "合成结果须与在飞工具调用按 tool_use_id 配对，实际: " + synthetic.payload());
        assertTrue(synthetic.payload().contains("error"), "合成结果状态应为错误");
        // then（中断不以 session.error 表达，终态仅为收场二事件 interrupted）
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_ERROR),
                "中断不得落 session.error");
        assertTerminalTail(saved, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE, "interrupted");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }

    // ==================== 4. 执行出错 ====================

    @Test
    void should_emitErrorTimelineInSeqOrder_when_sendMessageAsync_given_streamError() {
        // given（执行流异常：错误可恢复，收敛回 idle 并落 session.error）
        AgentSession session = idleSession();
        wireRound(session);
        stubStream(Flux.error(new RuntimeException("model 调用失败")));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（错误事件占内容位次、收场二事件压尾，seq 无空洞）
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.SESSION_ERROR,
                ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE);
        assertTrue(saved.get(2).payload().contains("DEEP_AGENT_RUN_ERROR"), "session.error 应携带错误码");
        assertTerminalTail(saved, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE, "error");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }

    // ==================== 5. 迭代上限 ====================

    @Test
    void should_emitTerminatedTimelineWithMaxIterations_when_sendMessageAsync_given_exceedMaxIters() {
        // given（触达迭代上限：正常终态但会话进 terminated）
        AgentSession session = idleSession();
        wireRound(session);
        stubStream(Flux.just(
                AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null),
                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（收场二事件落 terminated 语义，seq 连续）
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_TERMINATED,
                ChatEventType.SESSION_STATUS_TERMINATED);
        assertTerminalTail(saved, ChatEventType.SESSION_THREAD_STATUS_TERMINATED,
                ChatEventType.SESSION_STATUS_TERMINATED, "max_iterations");
        verify(sessionRepository).transition(session.sessionId(), Transition.TERMINATE);
    }

    // ==================== 6. 归档 ====================

    @Test
    void should_onlyWriteArchivedAtWithoutAnyEvent_when_archiveSession_given_idleSession() {
        // given（归档是正交维度：只置 archived_at，不改状态、不产生归档状态事件）
        AgentSession session = idleSession();
        wireTransactionTemplate();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        bindConnection(session);
        when(sessionRepository.archive(session.sessionId())).thenReturn(1);

        // when
        sessionLifecycleService.archiveSession(session.sessionId());

        // then（唯一落点为 archive；状态机 CAS 零调用）
        verify(sessionRepository).archive(session.sessionId());
        verify(sessionRepository, never()).transition(anyString(), any(Transition.class));
        // then（零事件：既不落库也不推送任何状态事件）
        assertTrue(savedChatEvents().isEmpty(), "归档不得落任何事件");
        verify(connectionHandle, never()).push(any(ChatEvent.class));
        // then（订阅收束 + 协调层显式释放租约）
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
    }

    // ==================== 7. 删除 ====================

    @Test
    void should_broadcastDeletedEventWithoutPersisting_when_deleteSession_given_existingSession() {
        // given（删除：级联清理前先向在线订阅者实时广播 session.deleted，该事件不落库）
        AgentSession session = idleSession();
        wireTransactionTemplate();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        bindConnection(session);
        when(chatEventRepository.deleteBySessionId(session.sessionId())).thenReturn(3);
        when(sessionThreadRepository.deleteBySessionId(session.sessionId())).thenReturn(1);
        when(sessionRepository.deleteBySessionId(session.sessionId())).thenReturn(1);

        // when
        sessionLifecycleService.deleteSession(session.sessionId());

        // then（session.deleted 仅实时推送：账本不新增行——会话历史即将整体删除）
        verify(connectionHandle).push(argThat(e -> e.type() == ChatEventType.SESSION_DELETED));
        assertTrue(savedChatEvents().isEmpty(), "session.deleted 不得落库");
        // then（级联清理次序：事件流 → 线程 → 会话行）
        InOrder cascade = inOrder(chatEventRepository, sessionThreadRepository, sessionRepository);
        cascade.verify(chatEventRepository).deleteBySessionId(session.sessionId());
        cascade.verify(sessionThreadRepository).deleteBySessionId(session.sessionId());
        cascade.verify(sessionRepository).deleteBySessionId(session.sessionId());
        // then（协调层释放租约 + 内存上下文移除）
        verify(coordinationLeaseService).releaseTurnLease(session.sessionId());
        assertTrue(sessionRegistry.get(session.sessionId()).isEmpty(), "删除后内存会话上下文应移除");
    }

    // ==================== 8. 断连不取消 ====================

    @Test
    void should_notCancelInFlightRound_when_connectionUnboundMidRound_given_sseDisconnect() {
        // given（轮次进行中订阅者断连：连接句柄被替换为空操作句柄——SSE 只是观察 / 回放通道）
        AgentSession session = idleSession();
        BuiltAgent agent = wireRound(session);
        stubStream(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"))
                .doOnNext(ignored -> sessionRegistry.get(session.sessionId())
                        .ifPresent(ctx -> ctx.bindConnection(NoOpConnectionHandle.INSTANCE)))
                .concatWith(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-1"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null))));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then（断连不取消：不投递任何定向中断，本轮照常收敛为正常终态）
        verify(agent, never()).interrupt(anyString(), anyString());
        List<ChatEvent> saved = savedChatEvents();
        assertTimeline(saved,
                ChatEventType.SESSION_STATUS_RUNNING,
                ChatEventType.SESSION_THREAD_STATUS_RUNNING,
                ChatEventType.AGENT_MESSAGE,
                ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE);
        assertTerminalTail(saved, ChatEventType.SESSION_THREAD_STATUS_IDLE,
                ChatEventType.SESSION_STATUS_IDLE, "end_turn");
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }
}
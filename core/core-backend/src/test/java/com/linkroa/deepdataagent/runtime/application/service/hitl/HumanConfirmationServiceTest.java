package com.linkroa.deepdataagent.runtime.application.service.hitl;

import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeServiceTestSupport;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HumanConfirmationService} 单测（decompose-command-facade 4.1：HITL 簇用例自
 * 门面壳测试类整体迁移，断言与桩一字未减）。
 * <p>用例经真实协作网端到端固化断言（装配见 {@link AgentRuntimeServiceTestSupport}）：
 * 挂起轮由 {@code turnExecutionService.sendMessageAsync} 真实执行进入等待确认态，
 * 确认 / 拒绝由本服务领取并续跑，令「事件表定位 → 租约抢占 → 领取 CAS → 明细重建 → 续跑收敛」
 * 的四条红线（严格排空 / D19 抛点 / 防止永久阻塞 / 挂起即轮终局）在子包镜像类里仍可整链断言。</p>
 */
class HumanConfirmationServiceTest extends AgentRuntimeServiceTestSupport {

    /**
     * 桩：HITL 事件表的工具调用集合与工具结果集合（durable 等待事实＝未应答工具调用行，
     * 「工具调用集合 − 已配对工具结果集合」即当前待确认批次，无 requires_action 旁路事件）。
     */
    private void stubsLedger(String sessionId, List<ChatEvent> toolUses, List<ChatEvent> toolResults) {
        when(chatEventRepository.findByTypes(sessionId, ChatEventType.TOOL_USE_TYPES)).thenReturn(toolUses);
        when(chatEventRepository.findByTypes(sessionId, ChatEventType.TOOL_RESULT_TYPES)).thenReturn(toolResults);
    }

    // ==================== HITL 人工确认 / 拒绝（durable：事件表事实 + 重建续跑） ====================

    @Test
    void should_throwNotFound_when_resolveHumanConfirmation_given_noPendingBatchInLedger() {
        // given（事件表无任何未应答工具调用行：无待确认项）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then（404 pending_action_not_found 语义：不领取、不续跑）
        assertThrows(ResourceNotFoundException.class, () -> humanConfirmationService.resolveHumanConfirmation(
                new ResolveHumanConfirmationCommand(session.sessionId(), true)));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_RESUME));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void should_rejectWithoutConsuming_when_resolveHumanConfirmation_given_anchorNotInCurrentBatch() {
        // given：事件表存在当前等待批次（一行未应答工具调用），指令携带不属于批次的越界锚点
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        ChatEvent batchHead = toolUseEvent(session.sessionId(), "tc-1", "search", "{\"q\":\"x\"}", 2L);
        stubsLedger(session.sessionId(), List.of(batchHead), List.of());

        // when & then：越界锚点 404 且不消费等待（不领取 CAS、不续跑，后续合法确认仍可解析）
        assertThrows(ResourceNotFoundException.class, () -> humanConfirmationService.resolveHumanConfirmation(
                new ResolveHumanConfirmationCommand(session.sessionId(), true, "evt_unrelated", null)));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_RESUME));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void should_returnNotFound_when_resolveHumanConfirmation_given_staleAnchorAfterInterrupt() {
        // given（旧等待批次被中断作废：其工具调用由中断配对补偿落了 result 而离开未应答集；
        //        新一轮再次挂起，用户持旧批次锚点迟到确认——仅未应答批次可锚定，旧锚点按不存在处理）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ChatEvent staleAnchor = toolUseEvent(session.sessionId(), "tc-old", "drop_table", "{}", 2L);
        ChatEvent freshAnchor = toolUseEvent(session.sessionId(), "tc-new", "search", "{}", 8L);
        stubsLedger(session.sessionId(), List.of(staleAnchor, freshAnchor),
                List.of(ChatEvent.create(session.sessionId(), ChatEventType.AGENT_TOOL_RESULT,
                        "{\"tool_use_id\":\"tc-old\",\"state\":\"error\"}", 3L)));

        // when & then（404 语义：越出当前批次的旧锚点不消费等待，无任何领取 / 续跑副作用）
        assertThrows(ResourceNotFoundException.class, () -> humanConfirmationService.resolveHumanConfirmation(
                new ResolveHumanConfirmationCommand(session.sessionId(), true, staleAnchor.eventId(), null)));
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_RESUME));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void should_resolveWholeBatchAndResume_when_resolveHumanConfirmation_given_multiToolCallPendingBatch() {
        // given：先真实挂起（批次两成员入事件表），事件表反查桩就位后提交确认
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        wireLedgerQueries();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(suspendBatchSignals());
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));
        String headEventId = savedChatEvents().stream()
                .filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE)
                .findFirst().orElseThrow().eventId();
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（批次内任一首元素锚定，裁决整批生效）
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), true, headEventId, null));

        // then：整批明细由事件表重建（SDK id / 工具名 / 入参），一次续跑注入全部结果
        ArgumentCaptor<List<PendingToolCallSpec>> specsCaptor = ArgumentCaptor.captor();
        verify(agentRunExecutor).resumeConfirmation(any(BuiltAgent.class), specsCaptor.capture(),
                eq(session.sessionId()), eq("1"), eq(true), isNull());
        List<PendingToolCallSpec> specs = specsCaptor.getValue();
        assertEquals(2, specs.size());
        assertEquals("tc-1", specs.get(0).toolCallId());
        assertEquals("search", specs.get(0).toolName());
        assertEquals("{\"q\":\"x\"}", specs.get(0).inputJson());
        assertEquals("tc-2", specs.get(1).toolCallId());
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_RESUME);
        // then：续跑轮正常终态收敛回 idle
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
    }

    @Test
    void should_attachMainThreadToAllEvents_when_sendMessageAsyncAndResume_given_suspendAndResumeRounds() {
        // given：会话存在存活主线程，先真实挂起（工具 + HITL 批次），再确认续跑至终态
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        SessionThread main = SessionThread.createMain(session.sessionId(), "{}");
        when(sessionThreadRepository.findMain(session.sessionId())).thenReturn(Optional.of(main));
        wireHappyPathForExecution();
        bindConnection(session);
        wireLedgerQueries();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(suspendBatchSignals());
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));
        String headEventId = savedChatEvents().stream()
                .filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE)
                .findFirst().orElseThrow().eventId();
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（确认续跑轮：领取事务内同样解析主线程归属）
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), true, headEventId, null));

        // then：两轮全部落库事件（含工具 / 挂起现场 / 终态 idle）均带主线程归属
        List<ChatEvent> saved = savedChatEvents();
        assertFalse(saved.isEmpty());
        assertTrue(saved.stream().allMatch(e -> main.threadId().equals(e.sessionThreadId())),
                "一轮执行全部事件应带主线程归属");
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.AGENT_TOOL_USE
                && main.threadId().equals(e.sessionThreadId())), "工具事件应带归属");
        // HITL 挂起在新契约下无状态事件：等待事实＝未应答工具调用事件表行（上一条已断言其归属），
        // 挂起只做内部相位迁移，故除 running / idle 外不得出现任何中间态会话状态事件
        assertTrue(saved.stream().noneMatch(e -> e.type().value().startsWith(ChatEventType.SESSION_STATUS_PREFIX)
                        && e.type() != ChatEventType.SESSION_STATUS_RUNNING
                        && e.type() != ChatEventType.SESSION_STATUS_IDLE),
                "挂起不得落任何中间态会话状态事件（等待事实由事件表承载）");
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_AWAIT);
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                && main.threadId().equals(e.sessionThreadId())), "终态事件应带归属");
    }

    @Test
    void should_resolveFromLedgerAndResume_when_resolveHumanConfirmation_given_pendingPersistedButNoInMemoryState() {
        // given（模拟重启 / 跨实例同形：服务实例无任何挂起轮执行历史，等待事实仅存事件表 + 内部相位）
        AgentSession session = idleSession().withPhase(TurnPhase.AWAITING_CONFIRMATION);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        ChatEvent toolUse = toolUseEvent(session.sessionId(), "tc-9", "drop_table", "{\"table\":\"t\"}", 2L);
        stubsLedger(session.sessionId(), List.of(toolUse), List.of());
        when(chatEventRepository.findToolUsesByEventIds(eq(session.sessionId()), anyList()))
                .thenReturn(List.of(toolUse));
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "确认后继续", "blk-9"),
                        AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null, "blk-9"),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（不依赖进程内存直接解析确认）
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), true, toolUse.eventId(), null));

        // then：领取 CAS → 事件表重建明细 → 续跑轮重新装配并收敛
        ArgumentCaptor<List<PendingToolCallSpec>> specsCaptor = ArgumentCaptor.captor();
        verify(agentRunExecutor).resumeConfirmation(any(BuiltAgent.class), specsCaptor.capture(),
                eq(session.sessionId()), eq("1"), eq(true), isNull());
        assertEquals(1, specsCaptor.getValue().size());
        assertEquals("tc-9", specsCaptor.getValue().get(0).toolCallId());
        List<ChatEvent> saved = savedChatEvents();
        // 续跑不写会话状态事件（对外 status 恒为 running，等待 / 续跑仅是内部相位迁移）
        assertTrue(saved.stream().noneMatch(e -> e.type() == ChatEventType.SESSION_STATUS_RUNNING),
                "续跑轮 MUST NOT 重复广播 session.status_running");
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.AGENT_MESSAGE
                        && e.payload().contains("确认后继续")),
                "续跑应续产 agent.message");
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        && e.payload().contains("\"type\":\"end_turn\"")),
                "续跑轮应正常收尾回 idle（stop_reason=end_turn）");
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_RESUME);
        verify(agent).close();
    }

    @Test
    void should_resolveMcpToolCallBatch_when_resolveHumanConfirmation_given_mcpToolUseLedgerRow() {
        // given（D15：MCP 调用产出 agent.mcp_tool_use，其 ask 求值暂停与内置工具同形——
        //        批次行可为 MCP 类型且工具名为运行时实名，确认链不得有类型分支）
        AgentSession session = idleSession().withPhase(TurnPhase.AWAITING_CONFIRMATION);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        ChatEvent mcpUse = ChatEvent.create(session.sessionId(), ChatEventType.AGENT_MCP_TOOL_USE,
                "{\"tool_use_id\":\"tc-1\",\"name\":\"mcp__weather__get_weather\",\"input\":{\"city\":\"上海\"},"
                        + "\"mcp_server_name\":\"weather\",\"evaluated_permission\":\"ask\"}", 2L);
        stubsLedger(session.sessionId(), List.of(mcpUse), List.of());
        when(chatEventRepository.findToolUsesByEventIds(eq(session.sessionId()), anyList()))
                .thenReturn(List.of(mcpUse));
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（用户以 MCP 调用事件 id 为暂停锚点提交 user.tool_confirmation=allow）
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), true, mcpUse.eventId(), null));

        // then（明细按 MCP 运行时实名重建并一次续跑注入；等待态经 CAS 领取）
        ArgumentCaptor<List<PendingToolCallSpec>> specsCaptor = ArgumentCaptor.captor();
        verify(agentRunExecutor).resumeConfirmation(any(BuiltAgent.class), specsCaptor.capture(),
                eq(session.sessionId()), eq("1"), eq(true), isNull());
        assertEquals(1, specsCaptor.getValue().size());
        assertEquals("tc-1", specsCaptor.getValue().get(0).toolCallId());
        assertEquals("mcp__weather__get_weather", specsCaptor.getValue().get(0).toolName());
        assertEquals("{\"city\":\"上海\"}", specsCaptor.getValue().get(0).inputJson());
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_RESUME);
    }

    @Test
    void should_returnConflict_when_resolveHumanConfirmation_given_concurrentDuplicateResolve() {
        // given：锚点命中批次，但领取 CAS 0 行（并发方胜出 / 等待已被解析）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        ChatEvent batchHead = toolUseEvent(session.sessionId(), "tc-1", "search", "{}", 2L);
        stubsLedger(session.sessionId(), List.of(batchHead), List.of());
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_RESUME)).thenReturn(0);

        // when & then（409 pending_action_already_resolved，无副作用续跑）
        assertThrows(ResourceConflictException.class, () -> humanConfirmationService.resolveHumanConfirmation(
                new ResolveHumanConfirmationCommand(session.sessionId(), true, batchHead.eventId(), null)));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void should_injectDenyWithMessage_when_resolveHumanConfirmation_given_denyCommand() {
        // given（事件表等待项；拒绝指令携带拒绝说明）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        ChatEvent batchHead = toolUseEvent(session.sessionId(), "tc-1", "drop_table", "{}", 2L);
        stubsLedger(session.sessionId(), List.of(batchHead), List.of());
        when(chatEventRepository.findToolUsesByEventIds(eq(session.sessionId()), anyList()))
                .thenReturn(List.of(batchHead));
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                eq(false), eq("删除操作不允许")))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), false, batchHead.eventId(), "删除操作不允许"));

        // then：拒绝裁决 + 说明经端口注入续跑，本轮正常收敛且 agent 释放
        verify(agentRunExecutor).resumeConfirmation(any(BuiltAgent.class), anyList(),
                eq(session.sessionId()), eq("1"), eq(false), eq("删除操作不允许"));
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(agent).close();
    }

    @Test
    void should_resolveWholeBatch_when_resolveHumanConfirmation_given_noAnchorToolUseId() {
        // given（等价于存量旧形态「批次无精确锚点」：确认指令不携带 tool_use_id 定位锚点，
        //        事件表当前未应答批次仍须整批解析——新契约锚点恒为公开 evt_ 事件 id，
        //        空锚点按「取当前未应答整批」处置，不再有旧 SDK 双键换算）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        ChatEvent toolUse = toolUseEvent(session.sessionId(), "tc-legacy", "search", "{}", 2L);
        stubsLedger(session.sessionId(), List.of(toolUse), List.of());
        when(chatEventRepository.findToolUsesByEventIds(eq(session.sessionId()), anyList()))
                .thenReturn(List.of(toolUse));
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when（便捷构造：不携带定位锚点）
        humanConfirmationService.resolveHumanConfirmation(
                new ResolveHumanConfirmationCommand(session.sessionId(), true));

        // then：整批明细由事件表重建（SDK id / 工具名取自 payload），续跑正常收敛
        ArgumentCaptor<List<PendingToolCallSpec>> specsCaptor = ArgumentCaptor.captor();
        verify(agentRunExecutor).resumeConfirmation(any(BuiltAgent.class), specsCaptor.capture(),
                eq(session.sessionId()), eq("1"), eq(true), isNull());
        assertEquals("tc-legacy", specsCaptor.getValue().get(0).toolCallId());
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_RESUME);
    }

    @Test
    void should_publishSucceededTurnFinishedOnce_when_resolveHumanConfirmation_given_suspendThenResumeEpisode() {
        // given：调度会话触发轮真实挂起（批次两成员入事件表）
        AgentSession session = scheduledIdleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        wireLedgerQueries();
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(suspendBatchSignals());
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));
        String headEventId = savedChatEvents().stream()
                .filter(e -> e.type() == ChatEventType.AGENT_TOOL_USE)
                .findFirst().orElseThrow().eventId();
        when(agentRunExecutor.resumeConfirmation(any(BuiltAgent.class), anyList(), anyString(), anyString(),
                anyBoolean(), any()))
                .thenReturn(Flux.just(AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));

        // when：确认续跑轮正常收口（同一 episode 的终局出口）
        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                session.sessionId(), true, headEventId, null));

        // then：终局事件发生在续跑轮且仅一次（挂起轮驻留零发布），按成功收口、无中断/失败终局
        verify(sessionRepository).transition(session.sessionId(), Transition.FINISH_TURN);
        verify(applicationEventPublisher, times(1))
                .publishEvent(new TurnFinished(session.sessionId(), "cron", "succeeded"));
        verify(applicationEventPublisher, never()).publishEvent(new TurnFinished(session.sessionId(), "cron", "terminated"));
        verify(applicationEventPublisher, never()).publishEvent(new TurnFinished(session.sessionId(), "cron", "failed"));
    }
}

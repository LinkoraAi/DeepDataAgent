package com.linkroa.deepdataagent.runtime.application.service.event;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeServiceTestSupport;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InboundEventService} 入站事件批量摄取单测（decompose-command-facade 4.2）。
 * <p>用例自门面壳测试类的入站分区整体迁入（断言逐字保留，
 * 仅调用目标由门面改为本服务），钉桩口径不变：</p>
 * <ul>
 *   <li>落库回显与实时广播：按到达顺序逐事件分配 seq 后落库，并向实时订阅者推送（回放兜底）；</li>
 *   <li>单活跃执行 409 门禁：含文本的 {@code user.message} 在落库前整批拒绝（零部分成功），
 *       纯控制事件批不受门禁限制；</li>
 *   <li>400 / 404 优先序 characterization：载荷类 400 先于会话不存在、出站类型 400 保持在属主
 *       校验之后（会话类错误优先）；</li>
 *   <li>按语义驱动 turn：{@code user.interrupt} 驱动取消链经 {@code Transition.PHASE_CANCEL} 相位 CAS。</li>
 * </ul>
 * <p>夹具沿用共享基座 {@link AgentRuntimeServiceTestSupport}：本服务与三个驱动目标
 * （执行链 / HITL / 会话生命周期）均为<b>真实实例 + 同一组替身端口</b>，
 * 故跨服务的驱动链路红线仍可端到端钉桩。</p>
 */
class InboundEventServiceTest extends AgentRuntimeServiceTestSupport {

    // ==================== 入站摄取：落库回显与驱动 ====================

    @Test
    void should_persistInboundInterrupt_when_ingestInboundEvents_given_userInterrupt() {
        // given（user.interrupt 入站事件：落库回显 + 广播，并驱动中断语义）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        bindConnection(session);
        when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        List<ChatEvent> persisted = inboundEventService.ingestInboundEvents(session.sessionId(),
                List.of(new InboundEventDraft(ChatEventType.USER_INTERRUPT, "{}")));

        // then（入站事件按到达顺序落库并向实时订阅者广播；中断指令驱动中断语义）
        assertEquals(1, persisted.size());
        assertEquals(ChatEventType.USER_INTERRUPT, persisted.get(0).type());
        verify(chatEventRepository).save(argThat(e -> e.type() == ChatEventType.USER_INTERRUPT));
        verify(connectionHandle).push(any(ChatEvent.class));
    }

    @Test
    void should_rejectWithConflict_when_ingestInboundEvents_given_activeExecutionUserMessage() {
        // given（会话有活跃执行（内部相位 running，对外 running）：新用户消息提交按 409 冲突拒绝）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then（SessionBusyException → HTTP 409；整批在落库前拒绝、零部分成功）
        assertThrows(SessionBusyException.class, () -> inboundEventService.ingestInboundEvents(
                session.sessionId(),
                List.of(new InboundEventDraft(ChatEventType.USER_MESSAGE,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}"))));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_passGateAndDriveCanceling_when_ingestInboundEvents_given_activeExecutionControlEvent() {
        // given（活跃执行期间控制事件批（user.interrupt）不受 409 门禁限制：正常落库并驱动取消链）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(1);
        // HITL 作废通道（awaiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);
        // 无在场面句柄：seq 经 DB max 兜底分配（nextSequenceNum 返回下一序号 1）
        when(chatEventRepository.nextSequenceNum(session.sessionId())).thenReturn(1L);
        when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        List<ChatEvent> persisted = inboundEventService.ingestInboundEvents(session.sessionId(),
                List.of(new InboundEventDraft(ChatEventType.USER_INTERRUPT, "{}")));

        // then（user.interrupt 正常落库回显 + 驱动取消链置 cancelling 相位）
        assertEquals(1, persisted.size());
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.USER_INTERRUPT));
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
    }

    // ==================== 400 / 404 优先序 characterization（tasks 2.4） ====================

    @Test
    void should_returnBadRequest400_when_ingestInboundEvents_given_sessionNotFoundAndPlainStringContent() {
        // given（会话不存在 + 纯字符串 content：载荷结构校验在方法体首行，先于会话归属查询）

        // when & then（validation_error 400 胜出，仓储零触碰）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> inboundEventService.ingestInboundEvents(
                "sess_absent", List.of(new InboundEventDraft(ChatEventType.USER_MESSAGE, "{\"content\":\"你好\"}"))));
        assertTrue(ex.getMessage().startsWith("validation_error"), "实际: " + ex.getMessage());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void should_returnBadRequest400_when_ingestInboundEvents_given_sessionNotFoundAndToolConfirmationMissingId() {
        // given（会话不存在 + user.tool_confirmation 缺 tool_use_id：载荷类 400 仍先于会话不存在）

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> inboundEventService.ingestInboundEvents(
                "sess_absent", List.of(new InboundEventDraft(ChatEventType.USER_TOOL_CONFIRMATION,
                        "{\"result\":\"allow\"}"))));
        assertTrue(ex.getMessage().startsWith("validation_error"), "实际: " + ex.getMessage());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void should_keepSessionErrorPrecedence_when_ingestInboundEvents_given_sessionNotFoundAndOutboundOnlyType() {
        // given（会话不存在 + 出站白名单类型 agent.message：出站投递 400 MUST NOT 随校验器前移）
        when(sessionRepository.findBySessionId("sess_absent")).thenReturn(Optional.empty());

        // when & then（属主校验在出站白名单之前 → 仍返回会话类错误，而非「不支持入站投递」）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> inboundEventService.ingestInboundEvents(
                        "sess_absent", List.of(new InboundEventDraft(ChatEventType.AGENT_MESSAGE, "{}"))));
        assertTrue(ex.getMessage().contains("会话不存在"), "实际: " + ex.getMessage());
        verify(sessionRepository).findBySessionId("sess_absent");
    }

    @Test
    void should_rejectOutboundTypeAfterOwnershipCheck_when_ingestInboundEvents_given_ownedSessionAndOutboundOnlyType() {
        // given（本人会话 + 出站类型：白名单 400 在属主校验之后照常拒绝）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then（整批 400，零落库）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> inboundEventService.ingestInboundEvents(
                session.sessionId(), List.of(new InboundEventDraft(ChatEventType.AGENT_MESSAGE, "{}"))));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"), "实际: " + ex.getMessage());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    // ==================== 写面门禁与会话启动事务合并（sessions / events spec） ====================

    @Test
    void should_rejectWithSessionBusy409_when_ingestInboundEvents_given_terminatedSessionAndUserMessage() {
        // given（已终止会话：终态不可再摄取新消息，按 409 invalid_request_error 拒绝）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.TERMINATED);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then（SessionBusyException → 409；消息不落库）
        assertThrows(SessionBusyException.class, () -> inboundEventService.ingestInboundEvents(
                session.sessionId(), List.of(new InboundEventDraft(ChatEventType.USER_MESSAGE,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}"))));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_rejectWithNotFound404_when_ingestInboundEvents_given_archivedSession() {
        // given（已归档会话：写面不可见，任意入站事件统一 404，不泄露存在性）
        AgentSession session = archivedSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> inboundEventService.ingestInboundEvents(
                session.sessionId(), List.of(new InboundEventDraft(ChatEventType.USER_INTERRUPT, "{}"))));
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_persistMessageInsideStartTransaction_when_ingestInboundEvents_given_userMessageBatch() {
        // given（user.message 批次：并入 turn 启动事务，起点为 CAS 抢占）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        // when
        List<ChatEvent> persisted = inboundEventService.ingestInboundEvents(session.sessionId(),
                List.of(new InboundEventDraft(ChatEventType.USER_MESSAGE,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}")));

        // then（权威次序：status_running → thread_status_running → user.message，同事务保序落库）
        assertEquals(3, persisted.size());
        assertEquals(ChatEventType.SESSION_STATUS_RUNNING, persisted.get(0).type());
        assertEquals(ChatEventType.SESSION_THREAD_STATUS_RUNNING, persisted.get(1).type());
        assertEquals(ChatEventType.USER_MESSAGE, persisted.get(2).type());
        assertTrue(persisted.get(0).seq() < persisted.get(2).seq(), "状态事件 seq 必须先于内容事件");
        verify(sessionRepository).transition(session.sessionId(), Transition.BEGIN_TURN);
    }
}

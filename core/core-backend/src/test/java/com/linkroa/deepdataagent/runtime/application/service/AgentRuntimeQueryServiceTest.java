package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.RunTraceRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRuntimeQueryService} 只读用例单测：会话 / 轮次 / 事件回放 / 链路追踪查询。
 * <p>不承载任何写操作与事务边界，仅验证查询编排与「会话 / 轮次存在性」前置校验.</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeQueryServiceTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ExecutionRoundRepository roundRepository;
    @Mock private ChatEventRepository chatEventRepository;
    @Mock private RunTraceRepository runTraceRepository;

    private AgentRuntimeQueryService service;

    @BeforeEach
    void setUp() {
        service = newQueryService();
    }

    private AgentRuntimeQueryService newQueryService() {
        AgentRuntimeQueryService svc = new AgentRuntimeQueryService();
        ReflectionTestUtils.setField(svc, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(svc, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(svc, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(svc, "runTraceRepository", runTraceRepository);
        return svc;
    }

    // ==================== 会话查询 ====================

    @Test
    void should_throwNotFound_when_getSession_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> service.getSession("nope"));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_SESSION_NOT_FOUND"));
    }

    @Test
    void should_returnSession_when_getSession_given_existingSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when
        AgentSession found = service.getSession(session.sessionId());

        // then
        assertEquals(session.sessionId(), found.sessionId());
    }

    @Test
    void should_returnPage_when_listSessions_given_validQuery() {
        // given
        when(sessionRepository.findByUserId("u-1", 1, 20)).thenReturn(List.of(idleSession()));
        when(sessionRepository.countByUserId("u-1")).thenReturn(1L);
        ListSessionsQuery query = new ListSessionsQuery("u-1", 1, 20);

        // when
        AgentRuntimeQueryService.PaginatedResult<AgentSession> page = service.listSessions(query);

        // then
        assertEquals(1, page.data().size());
        assertEquals(1L, page.total());
        assertEquals(1, page.page());
        assertEquals(20, page.size());
    }

    // ==================== 轮次 / 事件 / 追踪查询 ====================

    @Test
    void should_returnRounds_when_listRounds_given_validSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ExecutionRound round = ExecutionRound.create(session.sessionId(), "run-1", 1, "你好");
        when(roundRepository.findBySessionId(session.sessionId())).thenReturn(List.of(round));

        // when
        List<ExecutionRound> rounds = service.listRounds(session.sessionId());

        // then
        assertEquals(1, rounds.size());
        assertEquals("run-1", rounds.get(0).runId());
    }

    @Test
    void should_throwNotFound_when_roundEvents_given_missingRound() {
        // given
        when(roundRepository.findByRoundId("nope")).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> service.roundEvents("nope"));
        assertTrue(ex.getMessage().contains("DEEP_AGENT_ROUND_NOT_FOUND"));
    }

    @Test
    void should_returnEvents_when_roundEvents_given_validRound() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "你好");
        when(roundRepository.findByRoundId(round.roundId())).thenReturn(Optional.of(round));
        ChatEvent event = ChatEvent.create("s-1", round.roundId(), ChatEventType.MESSAGE, "{\"delta\":\"好\"}", 1L);
        when(chatEventRepository.findByRound(round.roundId())).thenReturn(List.of(event));

        // when
        List<ChatEvent> events = service.roundEvents(round.roundId());

        // then
        assertEquals(1, events.size());
        assertEquals(ChatEventType.MESSAGE, events.get(0).eventType());
    }

    @Test
    void should_returnReplayEvents_when_replayEvents_given_validSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        ChatEvent event = ChatEvent.create(session.sessionId(), "r-1", ChatEventType.RUN_START, "{}", 5L);
        when(chatEventRepository.findBySessionAfter(session.sessionId(), 3L)).thenReturn(List.of(event));

        // when
        List<ChatEvent> events = service.replayEvents(new ReplayQuery(session.sessionId(), 3L));

        // then
        assertEquals(1, events.size());
        assertEquals(5L, events.get(0).sequenceNum());
    }

    @Test
    void should_returnTrace_when_getTrace_given_validRound() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "你好");
        when(roundRepository.findByRoundId(round.roundId())).thenReturn(Optional.of(round));
        RunTrace root = RunTrace.createRoot("trace-1", round.roundId(), "agent.run");
        when(runTraceRepository.findByRound(round.roundId())).thenReturn(List.of(root));

        // when
        List<RunTrace> traces = service.getTrace(round.roundId());

        // then
        assertEquals(1, traces.size());
        assertEquals("agent.run", traces.get(0).spanName());
    }

    // ==================== 工具 ====================

    private AgentSession idleSession() {
        return AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
    }
}
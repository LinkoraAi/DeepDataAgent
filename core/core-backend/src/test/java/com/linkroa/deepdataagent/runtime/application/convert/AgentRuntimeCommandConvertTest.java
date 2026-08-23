package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.ResolveHumanConfirmationRequest;
import com.linkroa.deepdataagent.runtime.domain.model.SessionCursor;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentRuntimeCommandConvert} 装配器单测。
 */
class AgentRuntimeCommandConvertTest {

    private final AgentRuntimeCommandConvert assembler = AgentRuntimeCommandConvert.INSTANCE;

    @Test
    void should_buildCreateCommand_when_toCreateCommand_given_validRequest() {
        // given
        CreateSessionRequest request = new CreateSessionRequest(
                "agent-a", "env-1", "会话标题", null, null, Map.of("biz", "1"));

        // when
        CreateSessionCommand command = assembler.toCreateCommand("u-1", request, "1", "{\"biz\":\"1\"}");

        // then
        assertEquals("u-1", command.userId());
        assertEquals("agent-a", command.agentId());
        assertEquals("1", command.agentVersion());
        assertEquals("会话标题", command.title());
        assertEquals("{\"biz\":\"1\"}", command.metadata());
    }

    @Test
    void should_buildSendCommand_when_toSendCommand_given_sessionAndMessage() {
        // when
        SendMessageCommand command = assembler.toSendCommand("s-1", "你好", "run-1");

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals("你好", command.message());
        assertEquals("run-1", command.runId());
    }

    @Test
    void should_buildTerminateCommand_when_toTerminateCommand_given_sessionId() {
        // when
        TerminateSessionCommand command = assembler.toTerminateCommand("s-1");

        // then
        assertEquals("s-1", command.sessionId());
    }

    @Test
    void should_buildConfirmCommand_when_toResolveHumanConfirmationCommand_given_confirmedTrue() {
        // given
        ResolveHumanConfirmationRequest request = new ResolveHumanConfirmationRequest(true);

        // when
        ResolveHumanConfirmationCommand command = assembler.toResolveHumanConfirmationCommand("s-1", request);

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals(true, command.confirmed());
    }

    @Test
    void should_buildRejectCommand_when_toResolveHumanConfirmationCommand_given_confirmedFalse() {
        // given
        ResolveHumanConfirmationRequest request = new ResolveHumanConfirmationRequest(false);

        // when
        ResolveHumanConfirmationCommand command = assembler.toResolveHumanConfirmationCommand("s-1", request);

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals(false, command.confirmed());
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_defaultPagination() {
        // when
        ListSessionsQuery query = assembler.toListQuery("u-1", null, null, null, null);

        // then
        assertEquals("u-1", query.userId());
        assertEquals(null, query.agentId());
        assertEquals(List.of(), query.statuses());
        assertEquals(20, query.size());
        assertEquals(null, query.cursor());
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_filtersAndCursor() {
        // given
        String cursor = SessionCursor.of(OffsetDateTime.parse("2026-08-22T10:00:00+08:00"), 3L).encode();

        // when
        ListSessionsQuery query = assembler.toListQuery("u-1", "agent-a",
                List.of("RUNNING", "IDLE"), cursor, 50);

        // then
        assertEquals("agent-a", query.agentId());
        assertEquals(List.of(AgentSessionStatus.RUNNING, AgentSessionStatus.IDLE), query.statuses());
        assertEquals(50, query.size());
        assertEquals(3L, query.cursor().id());
    }

    @Test
    void should_throw_when_toListQuery_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toListQuery(" ", null, null, null, null));
    }

    @Test
    void should_throw_when_toListQuery_given_invalidStatus() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toListQuery("u-1", null, List.of("BOGUS"), null, null));
    }

    @Test
    void should_buildReplayQuery_when_toReplayQuery_given_positiveSequence() {
        // when
        ReplayQuery query = assembler.toReplayQuery("s-1", 10L);

        // then
        assertEquals("s-1", query.sessionId());
        assertEquals(10L, query.afterSequenceNum());
    }

    @Test
    void should_clampNegativeSequence_when_toReplayQuery_given_negativeSequence() {
        // when
        ReplayQuery query = assembler.toReplayQuery("s-1", -5L);

        // then
        assertEquals(0L, query.afterSequenceNum());
    }
}
package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentRuntimeCommandAssembler} 装配器单测。
 */
class AgentRuntimeCommandAssemblerTest {

    private final AgentRuntimeCommandAssembler assembler = new AgentRuntimeCommandAssemblerImpl();

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
    void should_buildListQuery_when_toListQuery_given_nullPagination() {
        // when
        ListSessionsQuery query = assembler.toListQuery("u-1", null, null);

        // then
        assertEquals("u-1", query.userId());
        assertEquals(1, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_explicitPagination() {
        // when
        ListSessionsQuery query = assembler.toListQuery("u-1", 50, 3);

        // then
        assertEquals(3, query.page());
        assertEquals(50, query.size());
    }

    @Test
    void should_throw_when_toListQuery_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> assembler.toListQuery(" ", null, null));
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
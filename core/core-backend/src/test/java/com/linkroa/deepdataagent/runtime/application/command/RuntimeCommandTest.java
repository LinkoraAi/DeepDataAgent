package com.linkroa.deepdataagent.runtime.application.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 应用层命令不变量单测（CreateSessionCommand / SendMessageCommand / TerminateSessionCommand）。
 */
class RuntimeCommandTest {

    // ===== CreateSessionCommand =====

    @Test
    void should_buildCommand_when_construct_given_validInputs() {
        // when
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // then
        assertEquals("u-1", command.userId());
        assertEquals("agent-a", command.agentId());
        assertEquals("1.0.0", command.agentVersion());
        assertEquals("会话", command.title());
        assertEquals("{}", command.metadata());
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("", "agent-a", "1.0.0", null, null));
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("u-1", " ", "1.0.0", null, null));
    }

    @Test
    void should_throw_when_construct_given_blankAgentVersion() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("u-1", "agent-a", null, null, null));
    }

    @Test
    void should_throw_when_construct_given_titleTooLong() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new CreateSessionCommand("u-1", "agent-a", "1.0.0", "t".repeat(256), null));
    }

    // ===== SendMessageCommand =====

    @Test
    void should_buildCommandWithGeneratedRunId_when_twoArgConstructor_given_validInputs() {
        // when
        SendMessageCommand command = new SendMessageCommand("s-1", "你好");

        // then
        assertEquals("s-1", command.sessionId());
        assertEquals("你好", command.message());
        assertNull(command.runId());
    }

    @Test
    void should_keepRunId_when_threeArgConstructor_given_runId() {
        // when
        SendMessageCommand command = new SendMessageCommand("s-1", "你好", "run-9");

        // then
        assertEquals("run-9", command.runId());
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new SendMessageCommand(" ", "你好"));
    }

    @Test
    void should_throw_when_construct_given_blankMessage() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new SendMessageCommand("s-1", ""));
    }

    // ===== TerminateSessionCommand =====

    @Test
    void should_buildCommand_when_construct_given_sessionId() {
        // when
        TerminateSessionCommand command = new TerminateSessionCommand("s-1");

        // then
        assertEquals("s-1", command.sessionId());
    }

    @Test
    void should_throwForTerminateCommand_when_construct_given_blankSessionId() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new TerminateSessionCommand(null));
    }
}
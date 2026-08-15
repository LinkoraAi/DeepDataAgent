package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentSession} 领域模型不变量单测。
 */
class AgentSessionTest {

    @Test
    void should_createIdleSession_when_create_given_validInputs() {
        // given
        String userId = "u-1";

        // when
        AgentSession session = AgentSession.create(userId, "agent-a", "1.0.0", null, "你好会话");

        // then
        assertNotNull(session.sessionId());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(userId, session.userId());
        assertEquals("agent-a", session.agentId());
        assertEquals("1.0.0", session.agentVersion());
        assertEquals("你好会话", session.title());
        assertEquals("{}", session.metadata());
        assertNotNull(session.createdAt());
        assertNotNull(session.updatedAt());
    }

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), "", factory.userId(), factory.agentId(),
                        factory.agentVersion(), factory.status(), factory.metadata(), factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), "", factory.agentId(),
                        factory.agentVersion(), factory.status(), factory.metadata(), factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), factory.userId(), " ",
                        factory.agentVersion(), factory.status(), factory.metadata(), factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankAgentVersion() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), factory.userId(), factory.agentId(),
                        null, factory.status(), factory.metadata(), factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullStatus() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), factory.userId(), factory.agentId(),
                        factory.agentVersion(), null, factory.metadata(), factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullMetadata() {
        // given
        AgentSession factory = validSession();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), factory.userId(), factory.agentId(),
                        factory.agentVersion(), factory.status(), null, factory.sandboxId(),
                        factory.title(), factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_titleTooLong() {
        // given
        AgentSession factory = validSession();
        String tooLong = "t".repeat(256);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSession(factory.id(), factory.sessionId(), factory.userId(), factory.agentId(),
                        factory.agentVersion(), factory.status(), factory.metadata(), factory.sandboxId(),
                        tooLong, factory.lastActiveAt(), factory.createdAt(), factory.updatedAt(),
                        factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_defaultMetadata_when_create_given_blankMetadata() {
        // given
        String blank = "  ";

        // when
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", blank, null);

        // then
        assertEquals("{}", session.metadata());
    }

    @Test
    void should_preserveMetadata_when_create_given_nonBlankMetadata() {
        // given
        String metadata = "{\"external_user_id\":\"eu-1\"}";

        // when
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", metadata, null);

        // then
        assertEquals(metadata, session.metadata());
    }

    @Test
    void should_defaultMetadata_when_restore_given_nullMetadata() {
        // given
        AgentSession factory = validSession();

        // when
        AgentSession restored = AgentSession.restore(
                factory.id(), factory.sessionId(), factory.userId(), factory.agentId(), factory.agentVersion(),
                factory.status(), null, factory.sandboxId(), factory.title(), factory.lastActiveAt(),
                factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy());

        // then
        assertEquals("{}", restored.metadata());
    }

    @Test
    void should_changeStatusWhen_withStatus_given_runningStatus() {
        // given
        AgentSession session = validSession();

        // when
        AgentSession running = session.withStatus(AgentSessionStatus.RUNNING);

        // then
        assertEquals(AgentSessionStatus.RUNNING, running.status());
        assertNotEquals(session, running);
    }

    private AgentSession validSession() {
        return AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
    }
}
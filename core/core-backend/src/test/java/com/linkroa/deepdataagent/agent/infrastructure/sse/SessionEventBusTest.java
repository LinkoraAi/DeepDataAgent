package com.linkroa.deepdataagent.agent.infrastructure.sse;

import io.agentscope.core.event.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SessionEventBus} 的单元测试
 * <p>验证会话事件路由的核心功能：注册、注销、事件流获取、状态检查及上限控制。</p>
 */
@ExtendWith(MockitoExtension.class)
public class SessionEventBusTest {

    @Mock
    private SessionEventBusProperties properties;

    @InjectMocks
    private SessionEventBus sessionEventBus;

    @BeforeEach
    public void setUp() {
        lenient().when(properties.maxActiveSessions()).thenReturn(500);
        lenient().when(properties.sinkBufferSize()).thenReturn(1024);
    }

    @Test
    public void should_returnNewSink_when_register_given_newSession() {
        // given
        String sessionId = "session-1";

        // when
        Sinks.Many<AgentEvent> sink = sessionEventBus.register(sessionId);

        // then
        assertNotNull(sink);
        assertTrue(sessionEventBus.isRegistered(sessionId));
        assertEquals(1, sessionEventBus.getActiveSessionCount());
    }

    @Test
    public void should_returnExistingSink_when_register_given_alreadyRegisteredSession() {
        // given
        String sessionId = "session-1";
        Sinks.Many<AgentEvent> firstSink = sessionEventBus.register(sessionId);

        // when
        Sinks.Many<AgentEvent> secondSink = sessionEventBus.register(sessionId);

        // then
        assertNotNull(secondSink);
        assertSame(firstSink, secondSink);
        assertEquals(1, sessionEventBus.getActiveSessionCount());
    }

    @Test
    public void should_returnNull_when_register_given_maxActiveSessionsReached() {
        // given
        when(properties.maxActiveSessions()).thenReturn(1);
        sessionEventBus.register("session-1");

        // when
        Sinks.Many<AgentEvent> sink = sessionEventBus.register("session-2");

        // then
        assertNull(sink);
        assertFalse(sessionEventBus.isRegistered("session-2"));
        assertEquals(1, sessionEventBus.getActiveSessionCount());
    }

    @Test
    public void should_removeSink_when_unregister_given_registeredSession() {
        // given
        String sessionId = "session-1";
        sessionEventBus.register(sessionId);

        // when
        sessionEventBus.unregister(sessionId);

        // then
        assertFalse(sessionEventBus.isRegistered(sessionId));
        assertEquals(0, sessionEventBus.getActiveSessionCount());
    }

    @Test
    public void should_doNothing_when_unregister_given_unregisteredSession() {
        // given
        String sessionId = "session-1";

        // when
        sessionEventBus.unregister(sessionId);

        // then
        assertFalse(sessionEventBus.isRegistered(sessionId));
        assertEquals(0, sessionEventBus.getActiveSessionCount());
    }

    @Test
    public void should_returnFlux_when_getEventStream_given_registeredSession() {
        // given
        String sessionId = "session-1";
        sessionEventBus.register(sessionId);

        // when
        Flux<AgentEvent> flux = sessionEventBus.getEventStream(sessionId);

        // then
        assertNotNull(flux);
    }

    @Test
    public void should_returnNull_when_getEventStream_given_unregisteredSession() {
        // given
        String sessionId = "session-1";

        // when
        Flux<AgentEvent> flux = sessionEventBus.getEventStream(sessionId);

        // then
        assertNull(flux);
    }

    @Test
    public void should_returnTrue_when_isRegistered_given_registeredSession() {
        // given
        String sessionId = "session-1";
        sessionEventBus.register(sessionId);

        // when
        boolean registered = sessionEventBus.isRegistered(sessionId);

        // then
        assertTrue(registered);
    }

    @Test
    public void should_returnFalse_when_isRegistered_given_unregisteredSession() {
        // given
        String sessionId = "session-1";

        // when
        boolean registered = sessionEventBus.isRegistered(sessionId);

        // then
        assertFalse(registered);
    }

    @Test
    public void should_returnCorrectCount_when_getActiveSessionCount_given_multipleRegistrations() {
        // given
        sessionEventBus.register("session-1");
        sessionEventBus.register("session-2");
        sessionEventBus.register("session-3");

        // when
        int count = sessionEventBus.getActiveSessionCount();

        // then
        assertEquals(3, count);
    }

    @Test
    public void should_decrementCount_when_unregister_given_oneOfMultipleSessions() {
        // given
        sessionEventBus.register("session-1");
        sessionEventBus.register("session-2");
        assertEquals(2, sessionEventBus.getActiveSessionCount());

        // when
        sessionEventBus.unregister("session-1");

        // then
        assertFalse(sessionEventBus.isRegistered("session-1"));
        assertTrue(sessionEventBus.isRegistered("session-2"));
        assertEquals(1, sessionEventBus.getActiveSessionCount());
    }
}
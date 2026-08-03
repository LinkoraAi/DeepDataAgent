package com.linkroa.deepdataagent.agent.infrastructure.sse;

import io.agentscope.core.event.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SSEConnectionPool} 的单元测试
 * <p>验证 SSE 连接池的核心功能：连接获取/复用、释放、事件发送、连接状态检查、
 * 活跃连接数统计、连接池上限控制以及空闲连接回收。</p>
 */
@ExtendWith(MockitoExtension.class)
class SSEConnectionPoolTest {

    @Mock
    private SSEConnectionPoolProperties properties;

    @InjectMocks
    private SSEConnectionPool connectionPool;

    @BeforeEach
    void setUp() {
        lenient().when(properties.maxActive()).thenReturn(500);
        lenient().when(properties.keepAliveMs()).thenReturn(30000L);
    }

    @Test
    void should_createNewConnection_when_acquire_given_newClientId() {
        // given
        String clientId = "client-1";

        // when
        SseEmitter emitter = connectionPool.acquire(clientId);

        // then
        assertNotNull(emitter);
        assertTrue(connectionPool.isConnected(clientId));
        assertEquals(1, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_reuseExistingConnection_when_acquire_given_existingClientId() {
        // given
        String clientId = "client-1";
        SseEmitter firstEmitter = connectionPool.acquire(clientId);

        // when
        SseEmitter secondEmitter = connectionPool.acquire(clientId);

        // then
        assertNotNull(firstEmitter);
        assertSame(firstEmitter, secondEmitter);
        assertEquals(1, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_removeConnection_when_release_given_existingClientId() {
        // given
        String clientId = "client-1";
        connectionPool.acquire(clientId);
        assertTrue(connectionPool.isConnected(clientId));

        // when
        connectionPool.release(clientId);

        // then
        assertFalse(connectionPool.isConnected(clientId));
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_doNothing_when_release_given_unknownClientId() {
        // given
        String clientId = "unknown-client";

        // when
        connectionPool.release(clientId);

        // then
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_returnTrue_when_sendEvent_given_validClientId() {
        // given
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        connectionPool.acquire(clientId);

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertTrue(result);
    }

    @Test
    void should_returnFalse_when_sendEvent_given_unknownClientId() {
        // given
        String clientId = "unknown-client";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnTrue_when_isConnected_given_existingClientId() {
        // given
        String clientId = "client-1";
        connectionPool.acquire(clientId);

        // when
        boolean connected = connectionPool.isConnected(clientId);

        // then
        assertTrue(connected);
    }

    @Test
    void should_returnFalse_when_isConnected_given_unknownClientId() {
        // given
        String clientId = "unknown-client";

        // when
        boolean connected = connectionPool.isConnected(clientId);

        // then
        assertFalse(connected);
    }

    @Test
    void should_returnActiveCount_when_getActiveConnectionCount_given_multipleConnections() {
        // given
        connectionPool.acquire("client-1");
        connectionPool.acquire("client-2");
        connectionPool.acquire("client-3");

        // when
        int count = connectionPool.getActiveConnectionCount();

        // then
        assertEquals(3, count);
    }

    @Test
    void should_returnNull_when_acquire_given_poolExhausted() {
        // given
        when(properties.maxActive()).thenReturn(1);
        connectionPool.acquire("client-1");

        // when
        SseEmitter emitter = connectionPool.acquire("client-2");

        // then
        assertNull(emitter);
        assertEquals(1, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_removeIdleConnection_when_reapIdleConnections_given_idleConnection() throws Exception {
        // given
        String clientId = "client-1";
        connectionPool.acquire(clientId);
        assertTrue(connectionPool.isConnected(clientId));

        // 关闭自动调度器，避免干扰
        Field schedulerField = SSEConnectionPool.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ScheduledExecutorService scheduler = (ScheduledExecutorService) schedulerField.get(connectionPool);
        scheduler.shutdownNow();

        // 设置 keepAliveMs 为 0，使连接立即变为空闲
        when(properties.keepAliveMs()).thenReturn(0L);

        // 通过反射设置 lastActiveTime 为过去的时间，确保连接被判定为空闲
        Field lastActiveTimesField = SSEConnectionPool.class.getDeclaredField("lastActiveTimes");
        lastActiveTimesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> lastActiveTimes = (Map<String, Long>) lastActiveTimesField.get(connectionPool);
        lastActiveTimes.put(clientId, System.currentTimeMillis() - 1000L);

        // 通过反射调用私有方法 reapIdleConnections
        Method reapMethod = SSEConnectionPool.class.getDeclaredMethod("reapIdleConnections");
        reapMethod.setAccessible(true);
        reapMethod.invoke(connectionPool);

        // then
        assertFalse(connectionPool.isConnected(clientId));
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }
}
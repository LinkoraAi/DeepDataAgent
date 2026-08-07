package com.linkroa.deepdataagent.agent.infrastructure.sse;

import io.agentscope.core.event.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

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
    void should_createConnectionWithoutTimeout_when_acquire_given_newClientId() {
        // given
        String clientId = "client-1";

        // when
        SseEmitter emitter = connectionPool.acquire(clientId);

        // then
        // 连接以 SseEmitter(0L) 创建，超时值应为 0（Servlet 规范：0 表示不设置容器级异步超时），
        // 避免连接在第 30 秒被容器强制断开，导致刷新后恢复续流失效
        assertNotNull(emitter);
        assertEquals(0L, emitter.getTimeout());
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
    void should_clearStaleSessionMapping_when_sendEvent_given_disconnectedClient() {
        // given
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        // 建立会话映射，但连接已断开（从未 acquire 或已被移除）
        connectionPool.updateSessionClientId(sessionId, clientId);
        assertNotNull(connectionPool.getClientIdForSession(sessionId));

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertFalse(result);
        // 连接缺失时清理过期映射，避免后续事件重复告警并防止映射泄漏
        assertNull(connectionPool.getClientIdForSession(sessionId));
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
    void should_returnFalseAndRemoveConnection_when_sendEvent_given_clientAbort() throws Exception {
        // given
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        SseEmitter mockEmitter = mock(SseEmitter.class);
        doThrow(new AsyncRequestNotUsableException("客户端已断开"))
                .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
        // 用 mock 替换连接池中的真实 emitter，模拟客户端断开时写入失败
        Field connectionsField = SSEConnectionPool.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SseEmitter> connections = (Map<String, SseEmitter>) connectionsField.get(connectionPool);
        connections.put(clientId, mockEmitter);

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertFalse(result);
        // 客户端断开异常后连接应被清理
        assertFalse(connectionPool.isConnected(clientId));
    }

    @Test
    void should_removeConnection_when_onError_given_clientDisconnect() throws Exception {
        // given
        String clientId = "client-1";
        SseEmitter emitter = connectionPool.acquire(clientId);
        assertTrue(connectionPool.isConnected(clientId));

        // 触发 onError 回调（客户端断开类异常），验证连接被清理且无异常抛出
        triggerOnError(emitter, new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"));

        // then
        assertFalse(connectionPool.isConnected(clientId));
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_removeConnection_when_onError_given_unknownException() throws Exception {
        // given
        String clientId = "client-1";
        SseEmitter emitter = connectionPool.acquire(clientId);
        assertTrue(connectionPool.isConnected(clientId));

        // 触发 onError 回调（未知服务端异常），验证连接仍被清理且无异常抛出
        triggerOnError(emitter, new RuntimeException("unknown server error"));

        // then
        assertFalse(connectionPool.isConnected(clientId));
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_removeConnectionOnlyOnce_when_onErrorTriggeredRepeatedly_given_sameClientDisconnect() throws Exception {
        // given：同一次断连被多个入口先后检测（此处模拟 onError 被重复触发）
        String clientId = "client-1";
        SseEmitter emitter = connectionPool.acquire(clientId);
        assertTrue(connectionPool.isConnected(clientId));

        // when：第一次触发断连回调，真正移除连接
        triggerOnError(emitter, new AsyncRequestNotUsableException("client disconnected"));
        boolean removedAfterFirst = !connectionPool.isConnected(clientId);
        int countAfterFirst = connectionPool.getActiveConnectionCount();

        // 第二次触发（同一连接已被移除，removeConnectionIfPresent 返回 false，不再重复清理）
        triggerOnError(emitter, new AsyncRequestNotUsableException("client disconnected again"));

        // then：仅第一次真正移除，第二次幂等，活跃数不重复扣减
        assertTrue(removedAfterFirst);
        assertEquals(0, countAfterFirst);
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    @Test
    void should_keepConnectionRemoved_when_sendEventAfterDisconnect_given_connectionAlreadyRemoved() throws Exception {
        // given：连接已因断连被移除
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        SseEmitter emitter = connectionPool.acquire(clientId);
        triggerOnError(emitter, new AsyncRequestNotUsableException("client disconnected"));
        assertFalse(connectionPool.isConnected(clientId));

        // when：断连后再次尝试发送事件（走 emitter == null 分支）
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then：返回 false，连接保持已移除，不重复记录
        assertFalse(result);
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }

    /**
     * 通过反射触发 SseEmitter 的 onError 回调
     *
     * @param emitter   已注册回调的 SseEmitter
     * @param throwable 传递给 onError 的异常
     */
    private void triggerOnError(SseEmitter emitter, Throwable throwable) throws Exception {
        // Spring 7 中 onError 回调包装在 ResponseBodyEmitter.errorCallback（ErrorCallback）中
        Field callbackField = ResponseBodyEmitter.class.getDeclaredField("errorCallback");
        callbackField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Consumer<Throwable> callback = (Consumer<Throwable>) callbackField.get(emitter);
        callback.accept(throwable);
    }

    @Test
    void should_clearSessionMapping_when_sendEvent_given_clientAbort() throws Exception {
        // given
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        // 建立会话映射，模拟断线期间事件仍被投递到已断开的连接
        connectionPool.updateSessionClientId(sessionId, clientId);
        SseEmitter mockEmitter = mock(SseEmitter.class);
        doThrow(new AsyncRequestNotUsableException("客户端已断开"))
                .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
        Field connectionsField = SSEConnectionPool.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SseEmitter> connections = (Map<String, SseEmitter>) connectionsField.get(connectionPool);
        connections.put(clientId, mockEmitter);

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertFalse(result);
        // 断连写入失败时应清理会话映射，避免后续事件反复投递到死连接
        assertFalse(connectionPool.isConnected(clientId));
        assertNull(connectionPool.getClientIdForSession(sessionId));
    }

    @Test
    void should_clearSessionMapping_when_sendEvent_given_ioError() throws Exception {
        // given
        String clientId = "client-1";
        String sessionId = "session-1";
        AgentEvent event = mock(AgentEvent.class);
        connectionPool.updateSessionClientId(sessionId, clientId);
        SseEmitter mockEmitter = mock(SseEmitter.class);
        doThrow(new IOException("Connection reset"))
                .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
        Field connectionsField = SSEConnectionPool.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SseEmitter> connections = (Map<String, SseEmitter>) connectionsField.get(connectionPool);
        connections.put(clientId, mockEmitter);

        // when
        boolean result = connectionPool.sendEvent(clientId, sessionId, event);

        // then
        assertFalse(result);
        assertFalse(connectionPool.isConnected(clientId));
        assertNull(connectionPool.getClientIdForSession(sessionId));
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

    @Test
    void should_removeConnection_when_sendHeartbeats_given_sendFailure() throws Exception {
        // given
        String clientId = "client-1";
        // 先通过 acquire 建立真实连接（保证 activeCount 计数一致），再用 mock 替换其中的 emitter，
        // 以模拟客户端已断开时心跳发送失败
        connectionPool.acquire(clientId);
        SseEmitter mockEmitter = mock(SseEmitter.class);
        doThrow(new IOException("Connection reset"))
                .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
        Field connectionsField = SSEConnectionPool.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SseEmitter> connections = (Map<String, SseEmitter>) connectionsField.get(connectionPool);
        connections.put(clientId, mockEmitter);

        // when：触发 sendHeartbeats，心跳发送失败应清理死连接（断连探测）
        Method heartbeatMethod = SSEConnectionPool.class.getDeclaredMethod("sendHeartbeats");
        heartbeatMethod.setAccessible(true);
        heartbeatMethod.invoke(connectionPool);

        // then
        assertFalse(connectionPool.isConnected(clientId));
        assertEquals(0, connectionPool.getActiveConnectionCount());
    }
}
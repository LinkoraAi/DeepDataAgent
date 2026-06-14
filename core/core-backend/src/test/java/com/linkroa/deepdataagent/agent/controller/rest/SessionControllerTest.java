package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.SessionApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CloseSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetMessagesRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListSessionsRequest;
import com.linkroa.deepdataagent.agent.controller.response.MessageResponse;
import com.linkroa.deepdataagent.agent.controller.response.SessionListItem;
import com.linkroa.deepdataagent.agent.controller.response.SessionResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SessionController 单元测试
 * <p>测试会话管理 REST 接口的响应封装与异常处理。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionApplicationService sessionApplicationService;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionController(sessionApplicationService);
    }

    // ==================== createSession ====================

    @Test
    void should_returnSuccess_when_createSession_given_validRequest() {
        // given
        CreateSessionRequest request = new CreateSessionRequest(1L, 2L);
        SessionResponse expectedResponse = new SessionResponse(
                "session-123", "新对话", 1L, 2L, "active", 0, null, "2025-01-01 00:00:00", null);
        when(sessionApplicationService.createSession(1L, 2L)).thenReturn(expectedResponse);

        // when
        ApiResponse<SessionResponse> result = controller.createSession(request);

        // then
        assertTrue(result.success());
        assertEquals("session-123", result.data().id());
        verify(sessionApplicationService).createSession(1L, 2L);
    }

    @Test
    void should_returnError_when_createSession_given_nonexistentDatasource() {
        // given
        CreateSessionRequest request = new CreateSessionRequest(999L, 1L);
        when(sessionApplicationService.createSession(999L, 1L))
                .thenThrow(new IllegalArgumentException("数据源不存在: 999"));

        // when
        ApiResponse<SessionResponse> result = controller.createSession(request);

        // then
        assertFalse(result.success());
        assertEquals("400", result.code());
        assertTrue(result.message().contains("数据源不存在"));
    }

    @Test
    void should_returnError_when_createSession_given_sessionLimitReached() {
        // given
        CreateSessionRequest request = new CreateSessionRequest(1L, 1L);
        when(sessionApplicationService.createSession(1L, 1L))
                .thenThrow(new IllegalStateException("活跃会话数已达上限"));

        // when
        ApiResponse<SessionResponse> result = controller.createSession(request);

        // then
        assertFalse(result.success());
        assertEquals("429", result.code());
    }

    // ==================== listSessions ====================

    @Test
    void should_returnSuccess_when_listSessions_given_activeSessions() {
        // given
        List<SessionListItem> expectedList = List.of(
                new SessionListItem("session-1", "会话1", 1L, 1L, "active", 0, null, "2025-01-01 00:00:00"));
        when(sessionApplicationService.listSessions()).thenReturn(expectedList);

        // when
        ApiResponse<List<SessionListItem>> result = controller.listSessions(new ListSessionsRequest());

        // then
        assertTrue(result.success());
        assertEquals(1, result.data().size());
    }

    @Test
    void should_returnEmptyList_when_listSessions_given_noSessions() {
        // given
        when(sessionApplicationService.listSessions()).thenReturn(List.of());

        // when
        ApiResponse<List<SessionListItem>> result = controller.listSessions(new ListSessionsRequest());

        // then
        assertTrue(result.success());
        assertTrue(result.data().isEmpty());
    }

    // ==================== getSession ====================

    @Test
    void should_returnSuccess_when_getSession_given_validSessionId() {
        // given
        SessionResponse expectedResponse = new SessionResponse(
                "session-1", "测试会话", 1L, 1L, "active", 5, null, "2025-01-01 00:00:00", null);
        when(sessionApplicationService.getSession("session-1")).thenReturn(expectedResponse);

        // when
        ApiResponse<SessionResponse> result = controller.getSession(new GetSessionRequest("session-1"));

        // then
        assertTrue(result.success());
        assertEquals("session-1", result.data().id());
    }

    @Test
    void should_returnError_when_getSession_given_nonexistentSessionId() {
        // given
        when(sessionApplicationService.getSession("nonexistent"))
                .thenThrow(new IllegalArgumentException("会话不存在: nonexistent"));

        // when
        ApiResponse<SessionResponse> result = controller.getSession(new GetSessionRequest("nonexistent"));

        // then
        assertFalse(result.success());
        assertEquals("404", result.code());
    }

    // ==================== closeSession ====================

    @Test
    void should_returnSuccess_when_closeSession_given_validSessionId() {
        // given
        doNothing().when(sessionApplicationService).closeSession("session-1");

        // when
        ApiResponse<Void> result = controller.closeSession(new CloseSessionRequest("session-1"));

        // then
        assertTrue(result.success());
        verify(sessionApplicationService).closeSession("session-1");
    }

    @Test
    void should_returnError_when_closeSession_given_nonexistentSessionId() {
        // given
        doThrow(new IllegalArgumentException("会话不存在"))
                .when(sessionApplicationService).closeSession("nonexistent");

        // when
        ApiResponse<Void> result = controller.closeSession(new CloseSessionRequest("nonexistent"));

        // then
        assertFalse(result.success());
        assertEquals("404", result.code());
    }

    @Test
    void should_returnError_when_closeSession_given_alreadyClosedSession() {
        // given
        doThrow(new IllegalStateException("会话已关闭"))
                .when(sessionApplicationService).closeSession("session-1");

        // when
        ApiResponse<Void> result = controller.closeSession(new CloseSessionRequest("session-1"));

        // then
        assertFalse(result.success());
        assertEquals("400", result.code());
    }

    // ==================== getMessages ====================

    @Test
    void should_returnSuccess_when_getMessages_given_validSessionId() {
        // given
        List<MessageResponse> expectedMessages = List.of(
                new MessageResponse(1L, "session-1", "user", "你好", null, null, null, "2025-01-01 00:00:00"));
        when(sessionApplicationService.getMessages(eq("session-1"), anyInt(), anyInt()))
                .thenReturn(expectedMessages);

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("session-1", 50, 0));

        // then
        assertTrue(result.success());
        assertEquals(1, result.data().size());
        assertEquals("user", result.data().getFirst().role());
    }

    @Test
    void should_returnSuccess_when_getMessages_given_defaultParams() {
        // given
        when(sessionApplicationService.getMessages(eq("session-1"), anyInt(), anyInt()))
                .thenReturn(List.of());

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("session-1", 50, 0));

        // then
        assertTrue(result.success());
    }

    @Test
    void should_returnError_when_getMessages_given_nonexistentSessionId() {
        // given
        when(sessionApplicationService.getMessages(eq("nonexistent"), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("会话不存在"));

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("nonexistent", 50, 0));

        // then
        assertFalse(result.success());
        assertEquals("404", result.code());
    }
}

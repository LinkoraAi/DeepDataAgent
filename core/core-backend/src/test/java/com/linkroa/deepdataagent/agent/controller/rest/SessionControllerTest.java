package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionDTO;
import com.linkroa.deepdataagent.agent.application.dto.SessionListItemDTO;
import com.linkroa.deepdataagent.agent.application.service.SessionApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CloseSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetMessagesRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetSessionRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListSessionsRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateSessionRequest;
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
import static org.mockito.ArgumentMatchers.isNull;
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
        CreateSessionRequest request = new CreateSessionRequest(1L, 2L, 3L, "测试问题");
        SessionDTO sessionDTO = new SessionDTO(
                "session-123", "测试问题", 2L, 3L, "ACTIVE", null, "2025-01-01 00:00:00");
        when(sessionApplicationService.createSession(1L, 2L, 3L, "测试问题")).thenReturn(sessionDTO);

        // when
        ApiResponse<SessionResponse> result = controller.createSession(request);

        // then
        assertTrue(result.success());
        assertEquals("session-123", result.data().id());
        verify(sessionApplicationService).createSession(1L, 2L, 3L, "测试问题");
    }

    @Test
    void should_returnError_when_createSession_given_nonexistentDatasource() {
        // given
        CreateSessionRequest request = new CreateSessionRequest(1L, 999L, 1L, "测试问题");
        when(sessionApplicationService.createSession(1L, 999L, 1L, "测试问题"))
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
        CreateSessionRequest request = new CreateSessionRequest(1L, 1L, 1L, "测试问题");
        when(sessionApplicationService.createSession(1L, 1L, 1L, "测试问题"))
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
        List<SessionListItemDTO> expectedList = List.of(
                new SessionListItemDTO("session-1", "会话1", 1L, 1L, "ACTIVE", null, "2025-01-01 00:00:00", true));
        when(sessionApplicationService.listSessions(null, null)).thenReturn(expectedList);

        // when
        ApiResponse<List<SessionListItem>> result = controller.listSessions(new ListSessionsRequest(null, null));

        // then
        assertTrue(result.success());
        assertEquals(1, result.data().size());
        assertTrue(result.data().getFirst().running());
    }

    @Test
    void should_returnEmptyList_when_listSessions_given_noSessions() {
        // given
        when(sessionApplicationService.listSessions(null, null)).thenReturn(List.of());

        // when
        ApiResponse<List<SessionListItem>> result = controller.listSessions(new ListSessionsRequest(null, null));

        // then
        assertTrue(result.success());
        assertTrue(result.data().isEmpty());
    }

    @Test
    void should_passParams_when_listSessions_given_limitAndOffset() {
        // given
        List<SessionListItemDTO> expectedList = List.of(
                new SessionListItemDTO("session-2", "会话2", 2L, 2L, "ACTIVE", null, "2025-01-01 00:00:00", false));
        when(sessionApplicationService.listSessions(20, 40)).thenReturn(expectedList);

        // when
        ApiResponse<List<SessionListItem>> result = controller.listSessions(new ListSessionsRequest(20, 40));

        // then
        assertTrue(result.success());
        assertEquals(1, result.data().size());
        verify(sessionApplicationService).listSessions(20, 40);
    }

    // ==================== getSession ====================

    @Test
    void should_returnSuccess_when_getSession_given_validSessionId() {
        // given
        SessionDTO sessionDTO = new SessionDTO(
                "session-1", "测试会话", 1L, 1L, "ACTIVE", null, "2025-01-01 00:00:00");
        when(sessionApplicationService.getSession("session-1")).thenReturn(sessionDTO);

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

    // ==================== updateSession ====================

    @Test
    void should_returnSuccess_when_updateSession_given_validRequest() {
        // given
        UpdateSessionRequest request = new UpdateSessionRequest("session-1", "新标题");
        doNothing().when(sessionApplicationService).updateSessionTitle("session-1", "新标题");

        // when
        ApiResponse<Void> result = controller.updateSession(request);

        // then
        assertTrue(result.success());
        verify(sessionApplicationService).updateSessionTitle("session-1", "新标题");
    }

    @Test
    void should_returnError_when_updateSession_given_nonexistentSessionId() {
        // given
        doThrow(new IllegalArgumentException("会话不存在"))
                .when(sessionApplicationService).updateSessionTitle("nonexistent", "新标题");

        // when
        ApiResponse<Void> result = controller.updateSession(new UpdateSessionRequest("nonexistent", "新标题"));

        // then
        assertFalse(result.success());
        assertEquals("404", result.code());
    }

    @Test
    void should_returnError_when_updateSession_given_deletedSession() {
        // given
        doThrow(new IllegalStateException("会话已删除，无法更新标题"))
                .when(sessionApplicationService).updateSessionTitle("session-1", "新标题");

        // when
        ApiResponse<Void> result = controller.updateSession(new UpdateSessionRequest("session-1", "新标题"));

        // then
        assertFalse(result.success());
        assertEquals("400", result.code());
    }

    // ==================== getMessages ====================

    @Test
    void should_returnSuccess_when_getMessages_given_validSessionId() {
        // given
        List<MessageDTO> expectedMessages = List.of(
                new MessageDTO(1L, "session-1", 100L, "user", "MESSAGE", "你好", null, null, null, "2025-01-01 00:00:00", "COMPLETED"));
        when(sessionApplicationService.getMessages(eq("session-1"), anyInt(), isNull()))
                .thenReturn(expectedMessages);

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("session-1", 50, null));

        // then
        assertTrue(result.success());
        assertEquals(1, result.data().size());
        assertEquals("user", result.data().getFirst().role());
    }

    @Test
    void should_returnSuccess_when_getMessages_given_defaultParams() {
        // given
        when(sessionApplicationService.getMessages(eq("session-1"), isNull(), isNull()))
                .thenReturn(List.of());

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("session-1", null, null));

        // then
        assertTrue(result.success());
    }

    @Test
    void should_returnError_when_getMessages_given_nonexistentSessionId() {
        // given
        when(sessionApplicationService.getMessages(eq("nonexistent"), anyInt(), isNull()))
                .thenThrow(new IllegalArgumentException("会话不存在"));

        // when
        ApiResponse<List<MessageResponse>> result = controller.getMessages(new GetMessagesRequest("nonexistent", 50, null));

        // then
        assertFalse(result.success());
        assertEquals("404", result.code());
    }
}

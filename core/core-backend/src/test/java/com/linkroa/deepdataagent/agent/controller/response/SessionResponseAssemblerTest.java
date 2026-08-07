package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionResponseAssembler 单元测试
 * <p>验证 AgentSession 领域模型到 SessionResponse DTO 的转换逻辑。</p>
 */
class SessionResponseAssemblerTest {

    @Test
    void should_mapAllFields_when_toResponse_given_activeSession() {
        // given
        AgentSession session = new AgentSession("session-1", "测试会话", 1L, 10L, 20L, SessionStatus.ACTIVE);
        session.setLastMessageTime(LocalDateTime.of(2025, 1, 15, 10, 30, 0));
        session.setCreatedTime(LocalDateTime.of(2025, 1, 14, 9, 0, 0));
        session.setUpdatedTime(LocalDateTime.of(2025, 1, 15, 11, 0, 0));

        // when
        SessionResponse result = SessionResponseAssembler.toResponse(session);

        // then
        assertNotNull(result);
        assertEquals("session-1", result.id());
        assertEquals("测试会话", result.title());
        assertEquals(10L, result.datasourceId());
        assertEquals(20L, result.modelConfigId());
        assertEquals("ACTIVE", result.status());
        assertEquals("2025-01-15 10:30:00", result.lastMessageAt());
        assertEquals("2025-01-14 09:00:00", result.createdAt());
    }

    @Test
    void should_mapStatusAsUppercase_when_toResponse_given_closedSession() {
        // given
        AgentSession session = new AgentSession("session-2", "已关闭", 2L, 10L, 20L, SessionStatus.CLOSED);
        session.setCreatedTime(LocalDateTime.of(2025, 1, 14, 9, 0, 0));
        session.setUpdatedTime(LocalDateTime.of(2025, 1, 15, 11, 0, 0));

        // when
        SessionResponse result = SessionResponseAssembler.toResponse(session);

        // then
        assertEquals("CLOSED", result.status());
    }

    @Test
    void should_mapTimestampsAsNull_when_toResponse_given_nullTimestamps() {
        // given
        AgentSession session = new AgentSession("session-3", "新会话", 3L, 10L, 20L, SessionStatus.ACTIVE);
        session.setLastMessageTime(null);

        // when
        SessionResponse result = SessionResponseAssembler.toResponse(session);

        // then
        assertNotNull(result);
        assertNull(result.lastMessageAt());
        assertNotNull(result.createdAt());
    }
}

package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentSession 聚合根单元测试
 * <p>覆盖构造器、生命周期方法（close/archive）及状态判断逻辑。</p>
 */
class AgentSessionTest {

    private AgentSession session;

    @BeforeEach
    void setUp() {
        session = new AgentSession("session-1", "测试会话", 1L, 10L, 20L, SessionStatus.ACTIVE);
    }

    // ==================== 构造器 ====================

    @Test
    void should_initializeFields_when_constructed_given_validParams() {
        // then
        assertEquals("session-1", session.getId());
        assertEquals("测试会话", session.getTitle());
        assertEquals(1L, session.getUserId());
        assertEquals(10L, session.getDatasourceId());
        assertEquals(20L, session.getModelConfigId());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        assertEquals(0, session.getDeleted());
        assertNotNull(session.getCreatedTime());
        assertNotNull(session.getUpdatedTime());
    }

    @Test
    void should_createEmptyInstance_when_defaultConstructor() {
        // given & when
        AgentSession empty = new AgentSession();

        // then
        assertNull(empty.getId());
        assertNull(empty.getTitle());
        assertNull(empty.getUserId());
        assertNull(empty.getStatus());
    }

    // ==================== close() ====================

    @Test
    void should_changeStatusToClosed_when_close_given_activeSession() {
        // when
        session.close();

        // then
        assertEquals(SessionStatus.CLOSED, session.getStatus());
        assertNotNull(session.getUpdatedTime());
    }

    @Test
    void should_notChangeStatus_when_close_given_alreadyClosedSession() {
        // given
        session.close();

        // when
        session.close();

        // then
        assertEquals(SessionStatus.CLOSED, session.getStatus());
    }

    @Test
    void should_notChangeStatus_when_close_given_deletedSession() {
        // given
        session.archive();

        // when
        session.close();

        // then
        assertEquals(SessionStatus.DELETED, session.getStatus());
    }

    // ==================== archive() ====================

    @Test
    void should_markAsDeleted_when_archive() {
        // when
        session.archive();

        // then
        assertEquals(SessionStatus.DELETED, session.getStatus());
        assertEquals(1, session.getDeleted());
        assertNotNull(session.getUpdatedTime());
    }

    // ==================== isClosed() ====================

    @Test
    void should_returnFalse_when_isClosed_given_activeSession() {
        // then
        assertFalse(session.isClosed());
    }

    @Test
    void should_returnTrue_when_isClosed_given_closedSession() {
        // given
        session.close();

        // then
        assertTrue(session.isClosed());
    }

    @Test
    void should_returnTrue_when_isClosed_given_archivedSession() {
        // given
        session.archive();

        // then
        assertTrue(session.isClosed());
    }

    // ==================== canStartDialogue() ====================

    @Test
    void should_returnTrue_when_canStartDialogue_given_activeSession() {
        // then
        assertTrue(session.canStartDialogue());
    }

    @Test
    void should_returnFalse_when_canStartDialogue_given_closedSession() {
        // given
        session.close();

        // then
        assertFalse(session.canStartDialogue());
    }

    @Test
    void should_returnFalse_when_canStartDialogue_given_archivedSession() {
        // given
        session.archive();

        // then
        assertFalse(session.canStartDialogue());
    }

    // ==================== touchLastMessage() ====================

    @Test
    void should_updateLastMessageTime_when_touchLastMessage() {
        // given
        assertNull(session.getLastMessageTime());

        // when
        session.touchLastMessage();

        // then
        assertNotNull(session.getLastMessageTime());
        assertNotNull(session.getUpdatedTime());
    }

    // ==================== setters/getters ====================

    @Test
    void should_updateTitle_when_setTitle() {
        // when
        session.setTitle("新标题");

        // then
        assertEquals("新标题", session.getTitle());
    }

    @Test
    void should_updateStatusAndTime_when_setStatus() {
        // when
        session.setStatus(SessionStatus.CLOSED);

        // then
        assertEquals(SessionStatus.CLOSED, session.getStatus());
        assertNotNull(session.getUpdatedTime());
    }
}

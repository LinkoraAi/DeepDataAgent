package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentSession 聚合根单元测试
 * <p>覆盖构造器、生命周期方法（close）及状态判断逻辑。</p>
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
        assertNotNull(session.getLastMessageTime());
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
    void should_changeStatusToDeleted_when_close_given_activeSession() {
        // when
        session.close();

        // then
        assertEquals(SessionStatus.DELETED, session.getStatus());
        assertEquals(1, session.getDeleted());
        assertNotNull(session.getUpdatedTime());
    }

    @Test
    void should_throwException_when_close_given_alreadyDeletedSession() {
        // given
        session.close();

        // when & then
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> session.close()
        );
        assertTrue(exception.getMessage().contains("已删除"));
        assertEquals(SessionStatus.DELETED, session.getStatus());
    }

    // ==================== isClosed() ====================

    @Test
    void should_returnFalse_when_isClosed_given_activeSession() {
        // then
        assertFalse(session.isClosed());
    }

    @Test
    void should_returnTrue_when_isClosed_given_deletedSession() {
        // given
        session.close();

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
    void should_returnFalse_when_canStartDialogue_given_deletedSession() {
        // given
        session.close();

        // then
        assertFalse(session.canStartDialogue());
    }

    // ==================== touchLastMessage() ====================

    @Test
    void should_updateLastMessageTime_when_touchLastMessage() {
        // given
        LocalDateTime before = session.getLastMessageTime();
        assertNotNull(before);

        // when
        session.touchLastMessage();

        // then
        LocalDateTime after = session.getLastMessageTime();
        assertNotNull(after);
        assertFalse(after.isBefore(before));
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
    void should_restoreStatusAndTime_when_restoreStatus() {
        // when
        session.restoreStatus(SessionStatus.DELETED);

        // then
        assertEquals(SessionStatus.DELETED, session.getStatus());
        assertNotNull(session.getUpdatedTime());
    }
}

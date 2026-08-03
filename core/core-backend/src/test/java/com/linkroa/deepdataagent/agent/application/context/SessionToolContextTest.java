package com.linkroa.deepdataagent.agent.application.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SessionToolContext} 单元测试
 * <p>覆盖注册、获取、注销的常规流程、null 处理与状态分支。</p>
 */
class SessionToolContextTest {

    /** 被测对象 */
    private SessionToolContext sessionToolContext;

    @BeforeEach
    void setUp() {
        sessionToolContext = new SessionToolContext();
    }

    @Test
    @DisplayName("register: 正常注册后应能通过 getModelConfigId 获取到对应的 modelConfigId")
    void should_returnModelConfigId_when_register_given_validSessionIdAndModelConfigId() {
        // given
        String sessionId = "session-001";
        Long modelConfigId = 100L;

        // when
        sessionToolContext.register(sessionId, modelConfigId);
        Long result = sessionToolContext.getModelConfigId(sessionId);

        // then
        assertEquals(modelConfigId, result);
    }

    @Test
    @DisplayName("register: sessionId 已存在时再次注册应覆盖旧的 modelConfigId")
    void should_overrideModelConfigId_when_register_given_existingSessionId() {
        // given
        String sessionId = "session-001";
        sessionToolContext.register(sessionId, 100L);
        Long newModelConfigId = 200L;

        // when
        sessionToolContext.register(sessionId, newModelConfigId);
        Long result = sessionToolContext.getModelConfigId(sessionId);

        // then
        assertEquals(newModelConfigId, result);
    }

    @Test
    @DisplayName("register: sessionId 为 null 时不应注册任何上下文且不抛异常")
    void should_notRegister_when_register_givenNullSessionId() {
        // given
        String sessionId = null;
        Long modelConfigId = 100L;

        // when & then
        assertDoesNotThrow(() -> sessionToolContext.register(sessionId, modelConfigId));
    }

    @Test
    @DisplayName("register: modelConfigId 为 null 时不应注册任何上下文")
    void should_notRegister_when_register_givenNullModelConfigId() {
        // given
        String sessionId = "session-001";
        Long modelConfigId = null;

        // when
        sessionToolContext.register(sessionId, modelConfigId);
        Long result = sessionToolContext.getModelConfigId(sessionId);

        // then
        assertNull(result);
    }

    @Test
    @DisplayName("register: sessionId 与 modelConfigId 均为 null 时不应注册任何上下文且不抛异常")
    void should_notRegister_when_register_givenNullSessionIdAndNullModelConfigId() {
        // given
        String sessionId = null;
        Long modelConfigId = null;

        // when & then
        assertDoesNotThrow(() -> sessionToolContext.register(sessionId, modelConfigId));
    }

    @Test
    @DisplayName("getModelConfigId: 未注册的 sessionId 应返回 null")
    void should_returnNull_when_getModelConfigId_givenNotRegisteredSessionId() {
        // given
        String sessionId = "session-not-exist";

        // when
        Long result = sessionToolContext.getModelConfigId(sessionId);

        // then
        assertNull(result);
    }

    @Test
    @DisplayName("getModelConfigId: sessionId 为 null 时因 ConcurrentHashMap 限制应抛出 NullPointerException")
    void should_throwNullPointerException_when_getModelConfigId_givenNullSessionId() {
        // given
        String sessionId = null;

        // when & then
        assertThrows(NullPointerException.class, () -> sessionToolContext.getModelConfigId(sessionId));
    }

    @Test
    @DisplayName("unregister: 注销已注册的会话后应无法再获取到对应 modelConfigId")
    void should_removeModelConfigId_when_unregister_givenRegisteredSessionId() {
        // given
        String sessionId = "session-001";
        sessionToolContext.register(sessionId, 100L);

        // when
        sessionToolContext.unregister(sessionId);
        Long result = sessionToolContext.getModelConfigId(sessionId);

        // then
        assertNull(result);
    }

    @Test
    @DisplayName("unregister: 注销未注册的会话不应抛异常")
    void should_notThrow_when_unregister_givenNotRegisteredSessionId() {
        // given
        String sessionId = "session-not-exist";

        // when & then
        assertDoesNotThrow(() -> sessionToolContext.unregister(sessionId));
    }

    @Test
    @DisplayName("unregister: sessionId 为 null 时不应抛异常")
    void should_notThrow_when_unregister_givenNullSessionId() {
        // given
        String sessionId = null;

        // when & then
        assertDoesNotThrow(() -> sessionToolContext.unregister(sessionId));
    }
}
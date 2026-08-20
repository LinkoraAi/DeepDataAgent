package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AgentSessionContext} 会话级聚合（逻辑线程组）单测：
 * 身份层、事件序号层、状态层状态机、连接层绑定与执行层取消收口。
 */
class AgentSessionContextTest {

    private AgentSessionContext context;

    @BeforeEach
    void setUp() {
        context = new AgentSessionContext(AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null));
    }

    // ==================== 身份层 ====================

    @Test
    void should_keepSessionAndId_when_sessionId_given_createdContext() {
        // when & then（身份层：镜像与聚合键）
        assertNotNull(context.session());
        assertEquals(context.session().sessionId(), context.sessionId());
    }

    // ==================== 事件序号层 ====================

    @Test
    void should_incrementSequenceFromDbMax_when_nextSequence_given_dbMaxBaseline() {
        // given（首轮以 DB 最大序号为基准）
        context.beginRound(5);

        // when
        long first = context.nextSequence();
        long second = context.nextSequence();

        // then（事件序号层：DB 起步后内存原子递增，消除逐事件 SELECT MAX）
        assertEquals(6L, first);
        assertEquals(7L, second);
    }

    @Test
    void should_keepMonotonicAcrossRounds_when_nextSequence_given_dbMaxLowerThanCounter() {
        // given（首轮已分配到 9，事件落库滞后于内存分配）
        context.beginRound(5);
        for (int i = 0; i < 4; i++) {
            context.nextSequence();
        }

        // when（第二轮 DB max 仍为旧值：跨轮计数器 + max 语义保证不回退）
        context.beginRound(5);
        long next = context.nextSequence();

        // then（despite DB 查询滞后，会话级计数器保持单调递增）
        assertEquals(10L, next);
    }

    @Test
    void should_replaceRunState_when_beginRound_given_existingState() {
        // given
        AgentRunState first = context.beginRound(1);
        first.appendOutput("第一轮");

        // when
        AgentRunState second = context.beginRound(10);

        // then（每轮独立状态：本轮输出不污染下一轮）
        assertNotSame(first, second);
        assertNotSame(first, context.runState());
        assertEquals("", second.output());
    }

    // ==================== 状态层 ====================

    @Test
    void should_transitionToRunning_when_transitionState_given_idle() {
        // when
        context.transitionState(SessionState.RUNNING);

        // then
        assertEquals(SessionState.RUNNING, context.state());
    }

    @Test
    void should_throwOnIllegalTransition_when_transitionState_given_idleToDone() {
        // when & then（非法迁移抛异常）
        assertThrows(IllegalStateException.class, () -> context.transitionState(SessionState.DONE));
    }

    @Test
    void should_fallBackToIdle_when_transitionState_given_terminal() {
        // given（进入瞬态终态）
        context.transitionState(SessionState.RUNNING);
        context.transitionState(SessionState.DONE);

        // when（一轮结束回落 IDLE）
        context.transitionState(SessionState.IDLE);

        // then
        assertEquals(SessionState.IDLE, context.state());
    }

    @Test
    void should_returnFalse_when_tryTransitionState_given_illegalTransition() {
        // when & then（tryTransition 对非法迁移返回 false 而非抛异常）
        assertFalse(context.tryTransitionState(SessionState.DONE));
        assertEquals(SessionState.IDLE, context.state());
    }

    @Test
    void should_markInterrupted_when_cancel_given_running() {
        // given
        context.transitionState(SessionState.RUNNING);

        // when（断连 / 终止收口：执行取消 + 显式中断标记）
        context.cancel();

        // then
        assertEquals(SessionState.INTERRUPTED, context.state());
    }

    // ==================== 连接层 ====================

    @Test
    void should_defaultNoOpConnection_when_connection_given_newContext() {
        // when & then（默认 NoOp 句柄，无连接不产生副作用）
        assertEquals(NoOpConnectionHandle.INSTANCE, context.connection());
        assertFalse(context.connection().isActive());
    }

    @Test
    void should_bindConnection_when_bindConnection_given_handle() {
        // given
        ConnectionHandle handle = mock(ConnectionHandle.class);

        // when
        context.bindConnection(handle);

        // then
        assertEquals(handle, context.connection());
    }

    @Test
    void should_closeOldConnection_when_bindConnection_given_newHandle() {
        // given
        ConnectionHandle old = mock(ConnectionHandle.class);
        context.bindConnection(old);
        ConnectionHandle next = mock(ConnectionHandle.class);

        // when（原子替换时释放旧句柄，默认 NoOp 句柄不释放）
        context.bindConnection(next);

        // then
        assertEquals(next, context.connection());
        verify(old).close();
        verify(next, never()).close();
    }

    @Test
    void should_rejectNull_when_bindConnection_given_nullHandle() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> context.bindConnection(null));
    }
}
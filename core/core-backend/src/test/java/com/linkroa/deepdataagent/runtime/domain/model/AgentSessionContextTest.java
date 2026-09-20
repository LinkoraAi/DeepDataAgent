package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AgentSessionContext} 会话级聚合（逻辑线程组）单测：
 * 身份层、事件序号层、执行层（beginRound / 中断标记）与连接层绑定。
 * <p>单轨七态状态机由 DB {@code agent_session.status} 承载，本聚合不再保留内存状态机副本；
 * 轮次级瞬态状态机（SessionState / RoundStatus / HitlState）已随事件溯源重构移除。</p>
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
        TurnRunState first = context.beginRound(1);
        first.appendOutput("第一轮");

        // when
        TurnRunState second = context.beginRound(10);

        // then（每轮独立状态：本轮输出不污染下一轮）
        assertNotSame(first, second);
        assertNotSame(first, context.runState());
        assertEquals("", second.output());
    }

    // ==================== 执行层 ====================

    @Test
    void should_markInterrupted_when_interruptCurrentRun_given_activeRun() {
        // given（当前轮事件流累积态就绪）
        context.beginRound(1);

        // when（user.interrupt：标记显式中断 + 触发执行取消）
        context.interruptCurrentRun();

        // then（终态路径据此发送 session.interrupted 而非 cancelled 终态）
        assertTrue(context.runState().interrupted());
    }

    @Test
    void should_beNoop_when_interruptCurrentRun_given_noActiveRun() {
        // when（无运行中的轮次时中断为空操作，不抛出）
        context.interruptCurrentRun();
    }

    @Test
    void should_triggerInterrupter_when_cancel_given_currentTurnActivated() {
        // given（本轮控制面已置入并登记定向中断句柄）
        TurnControl turn = new TurnControl();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        turn.activate(() -> interrupted.set(true));
        context.beginTurn(turn);

        // when（cancel 指向 currentTurn 触发句柄）
        context.cancel();

        // then（句柄被触发；控制面保持指向本轮直至 finally 条件清除——cancel 不再摘槽）
        assertTrue(interrupted.get(), "cancel 须触发本轮已登记的中断句柄");
        assertSame(turn, context.currentTurn());
    }

    @Test
    void should_noop_when_cancel_given_noCurrentTurn() {
        // when & then（空闲无控制面时取消为空操作，不抛出）
        assertDoesNotThrow(() -> context.cancel());
    }

    @Test
    void should_clearCurrentTurn_when_endTurn_given_matchingTurn() {
        // given（启动时置入本轮控制面）
        TurnControl turn = new TurnControl();
        context.beginTurn(turn);

        // when（终局清除：槽位仍指向本轮）
        context.endTurn(turn);

        // then
        assertNull(context.currentTurn(), "endTurn 命中本轮对象须置空槽位");
    }

    @Test
    void should_keepNewTurn_when_endTurn_given_staleTurnAfterReplacement() {
        // given（毫秒窗内新一轮覆盖置位：旧轮仍持有上一轮的 staleTurn）
        TurnControl staleTurn = new TurnControl();
        context.beginTurn(staleTurn);
        TurnControl newTurn = new TurnControl();
        context.beginTurn(newTurn);

        // when（旧轮 finally 清除：槽位已指向新轮，条件生效不得误清）
        context.endTurn(staleTurn);

        // then（防毫秒窗误清：新轮控制面仍在位）
        assertSame(newTurn, context.currentTurn(), "旧轮 endTurn 不得误清新轮控制面");
    }

    @Test
    void should_rejectNull_when_beginTurn_given_nullTurn() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> context.beginTurn(null));
    }

    // ==================== 连接层 ====================

    @Test
    void should_defaultNoOpConnection_when_connection_given_newContext() {
        // when & then（默认 NoOp 句柄，无连接不产生副作用）
        assertEquals(NoOpConnectionHandle.INSTANCE, context.connection());
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
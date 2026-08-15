package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AgentSessionContext} 会话级聚合（逻辑线程组）单测：
 * 跨轮序列号单调递增、在跑执行串行守卫与断连中断幂等、每轮状态替换。
 */
class AgentSessionContextTest {

    private AgentSessionContext context;

    @BeforeEach
    void setUp() {
        context = new AgentSessionContext(AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null));
    }

    @Test
    void should_keepSessionAndId_when_sessionId_given_createdContext() {
        // when & then（身份层：镜像与聚合键）
        assertNotNull(context.session());
        assertEquals(context.session().sessionId(), context.sessionId());
    }

    @Test
    void should_incrementSequenceFromDbMax_when_nextSequence_given_dbdMaxBaseline() {
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
    void should_keepMonotonicAcrossRounds_when_nextSequence_given_dbdMaxLowerThanCounter() {
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

    @Test
    void should_registerActiveRun_when_registerActiveRun_given_noActiveRun() {
        // given（无在跑执行）

        // when
        boolean registered = context.registerActiveRun("r-1", mock(BuiltAgent.class));

        // then（执行层：首次注册成功且可查询）
        assertTrue(registered);
        assertTrue(context.activeRun().isPresent());
        assertEquals("r-1", context.activeRun().orElseThrow().roundId());
    }

    @Test
    void should_rejectSecondRun_when_registerActiveRun_given_activeRunExists() {
        // given（首轮已注册在跑）
        context.registerActiveRun("r-1", mock(BuiltAgent.class));

        // when
        boolean second = context.registerActiveRun("r-2", mock(BuiltAgent.class));

        // then（进程内串行守卫：同会话仅一个在跑执行，与 DB 单飞 CAS 互为双保险）
        assertFalse(second);
        assertEquals("r-1", context.activeRun().orElseThrow().roundId());
    }

    @Test
    void should_removeWithoutInterrupt_when_clearActiveRun_given_registeredRun() {
        // given
        BuiltAgent agent = mock(BuiltAgent.class);
        context.registerActiveRun("r-1", agent);

        // when
        context.clearActiveRun();

        // then（正常完成：仅清除注册，不触发 agent 中断）
        assertFalse(context.activeRun().isPresent());
        verify(agent, never()).interrupt();
    }

    @Test
    void should_interruptAgent_when_interruptActiveRun_given_registeredRun() {
        // given
        BuiltAgent agent = mock(BuiltAgent.class);
        context.registerActiveRun("r-1", agent);

        // when
        context.interruptActiveRun();

        // then（断连/终止：清除注册并幂等中断 agent）
        assertFalse(context.activeRun().isPresent());
        verify(agent).interrupt();
    }

    @Test
    void should_tolerateMissingRun_when_interruptActiveRun_given_noActiveRun() {
        // given（空闲会话无在跑执行）

        // when & then（幂等：无在跑执行时空操作不抛异常）
        context.interruptActiveRun();
        assertFalse(context.activeRun().isPresent());
    }

    @Test
    void should_swallowInterruptException_when_interruptActiveRun_given_throwingAgent() {
        // given
        BuiltAgent agent = mock(BuiltAgent.class);
        doThrow(new RuntimeException("interrupt 失败")).when(agent).interrupt();
        context.registerActiveRun("r-1", agent);

        // when & then（底层异常被隔离，注册状态已清理）
        context.interruptActiveRun();
        assertFalse(context.activeRun().isPresent());
    }

    @Test
    void should_clearActiveRunAfterInterrupt_when_interruptActiveRun_given_twice() {
        // given
        BuiltAgent agent = mock(BuiltAgent.class);
        context.registerActiveRun("r-1", agent);

        // when（重复中断：第二次为空操作）
        context.interruptActiveRun();
        context.interruptActiveRun();

        // then（中断幂等：agent 仅触发一次）
        verify(agent).interrupt();
        assertFalse(context.activeRun().isPresent());
    }
}
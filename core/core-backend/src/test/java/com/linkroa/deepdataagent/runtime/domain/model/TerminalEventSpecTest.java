package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalEventKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TerminalEventSpec} 值对象不变量单测（决策表输出侧的事件语义载体）。
 * <p>只锁语义（种类 + 状态 + 终态原因 + 错误码/信息），不涉 payload JSON——
 * 后者由领域事件装配工厂 {@code ChatEventFactory}（domain.event）按对外契约装配。</p>
 * <p>收场事件集按「线程先、会话后」镜像：{@code session.interrupted} 已废止，
 * 中断不以独立事件表达，改由 {@link TerminalEventSpec#threadStatusEvent} +
 * {@link TerminalEventSpec#statusEvent} 成对承载。</p>
 */
class TerminalEventSpecTest {

    @Test
    void should_carryStatusAndReason_when_statusEvent_given_targetStatus() {
        // given / when
        TerminalEventSpec spec = TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.STOP);

        // then（正常结束的对外值为 end_turn）
        assertEquals(TerminalEventKind.SESSION_STATUS, spec.kind());
        assertEquals(AgentSessionStatus.IDLE, spec.status());
        assertEquals("end_turn", spec.stopReason().value());
        assertNull(spec.errorCode());
    }

    @Test
    void should_allowNullStopReason_when_statusEvent_given_midwayStatusEvent() {
        // given / when：中段状态事件（如 running）无 stop_reason
        TerminalEventSpec spec = TerminalEventSpec.statusEvent(AgentSessionStatus.RUNNING, null);

        // then
        assertEquals(AgentSessionStatus.RUNNING, spec.status());
        assertNull(spec.stopReason());
    }

    @Test
    void should_carryStatusAndReason_when_threadStatusEvent_given_targetStatus() {
        // given / when（主线程状态镜像事件：与会话状态事件共享同一 stop_reason）
        TerminalEventSpec spec = TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                TerminalStopReason.INTERRUPTED);

        // then
        assertEquals(TerminalEventKind.THREAD_STATUS, spec.kind());
        assertEquals(AgentSessionStatus.IDLE, spec.status());
        assertEquals("interrupted", spec.stopReason().value());
        assertNull(spec.errorCode());
        assertNull(spec.errorMessage());
    }

    @Test
    void should_throwIllegalArgument_when_statusEvent_given_missingStatus() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalEventSpec(TerminalEventKind.SESSION_STATUS, null,
                        TerminalStopReason.STOP, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalEventSpec(TerminalEventKind.THREAD_STATUS, null,
                        TerminalStopReason.STOP, null, null));
    }

    @Test
    void should_carryErrorPayload_when_errorEvent_givenCodeAndMessage() {
        // given / when
        TerminalEventSpec spec = TerminalEventSpec.errorEvent("DEEP_AGENT_RUN_ERROR", "上游 500");

        // then：错误事件不携带状态（状态由同事务的状态事件承载）
        assertEquals(TerminalEventKind.SESSION_ERROR, spec.kind());
        assertEquals("DEEP_AGENT_RUN_ERROR", spec.errorCode());
        assertEquals("上游 500", spec.errorMessage());
        assertNull(spec.status());
        assertNull(spec.stopReason());
    }

    @Test
    void should_throwIllegalArgument_when_errorEvent_given_missingCodeOrMessage() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> TerminalEventSpec.errorEvent(null, "消息"));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalEventSpec.errorEvent("CODE", " "));
    }

    @Test
    void should_throwIllegalArgument_when_constructor_given_nullKind() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalEventSpec(null, AgentSessionStatus.IDLE, null, null, null));
    }
}
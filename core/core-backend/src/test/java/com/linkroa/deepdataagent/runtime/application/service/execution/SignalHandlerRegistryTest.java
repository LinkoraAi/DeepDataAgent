package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 信号注册表全链路快照测试（变更 R11 task 4.4）：逐一驱动 {@link AgentStreamSignalType} 全部信号类型，
 * 断言每类信号经 {@link SignalHandlerRegistry} 落到其属主策略并产生预期的可观测副作用。
 * <p>该断言同时是「注册完整性 + 路由正确性」证明：若某类型未登记，将走 {@code WARN_UNKNOWN}（无对应副作用）
 * 而令期望落空；若被错误路由到别家策略，则触发该策略 {@code default} 的 {@link IllegalStateException}。
 * slim 删除 START 后按 16 类核对。</p>
 */
class SignalHandlerRegistryTest {

    private final SignalHandlerRegistry registry = new SignalHandlerRegistry();

    @ParameterizedTest
    @EnumSource(AgentStreamSignalType.class)
    void should_dispatchToOwningStrategy_when_dispatch_given_eachSignalType(AgentStreamSignalType type) {
        // given
        TurnRunState runState = new TurnRunState();
        RecordingRoundSink sink = new RecordingRoundSink();

        // when
        registry.dispatch(newContext(representative(type), runState, sink));

        // then：观测副作用与属主策略期望一致
        assertEquals(expectedEffect(type), observedEffect(sink, runState),
                "信号类型 " + type + " 应路由到其属主策略");
    }

    @Test
    void should_completeEverySignalType_when_dispatch_given_registryCoversAllEnums() {
        // given / when / then：注册表覆盖全部枚举，逐类型分发均不抛（错路由会命中属主 default 抛 ISE）
        for (AgentStreamSignalType type : AgentStreamSignalType.values()) {
            RecordingRoundSink sink = new RecordingRoundSink();
            registry.dispatch(newContext(representative(type), new TurnRunState(), sink));
        }
        assertEquals(16, AgentStreamSignalType.values().length, "slim 删除 START 后信号类型应为 16 类");
    }

    @Test
    void should_throwIllegalState_when_handle_given_signalRoutedToWrongStrategy() {
        // given：文本信号被错误交给工具策略（属主守卫防御）
        TurnRunState runState = new TurnRunState();
        RecordingRoundSink sink = new RecordingRoundSink();
        SignalContext ctx = newContext(
                AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "t", "blk-1"), runState, sink);

        // when & then：ToolCallSignalHandler 收到 TEXT_DELTA 抛 IllegalStateException
        assertThrows(IllegalStateException.class, () -> new ToolCallSignalHandler().handle(ctx));
    }

    // ==================== 夹具 ====================

    /** 按信号类型构造一条最小可代表信号（覆盖各策略的入参形态）。 */
    private static AgentStreamSignal representative(AgentStreamSignalType type) {
        return switch (type) {
            case TEXT_DELTA, TEXT_END, THINKING_DELTA, THINKING_END, MODEL_CALL_START,
                    AGENT_RESULT, EXCEED_MAX_ITERS, AGENT_END ->
                    AgentStreamSignal.of(type, "x", "blk-1");
            case TOOL_CALL_START -> AgentStreamSignal.tool(type, "tc-1", "search", null, null);
            case TOOL_CALL_DELTA -> AgentStreamSignal.tool(type, "tc-1", "search", "{\"a\":1}", null);
            case TOOL_CALL_END -> AgentStreamSignal.tool(type, "tc-1", "search", null, null);
            case TOOL_RESULT_TEXT_DELTA -> AgentStreamSignal.tool(type, "tc-1", "search", "r", null);
            case TOOL_RESULT_END -> AgentStreamSignal.tool(type, "tc-1", "search", null, "OK");
            case MODEL_CALL_END -> new AgentStreamSignal(type, null, null, null, null, null, null,
                    10, 20, "gpt-4", null, null);
            case HUMAN_CONFIRM_REQUIRED -> AgentStreamSignal.hitl(type, "reply-1", List.of("tc-1"));
            case HUMAN_CONFIRM_RESULT -> AgentStreamSignal.hitl(type, "reply-1");
        };
    }

    /** 归一化观测效果：终态 / 挂起 / 落库事件类型 / 推帧目标 / 无副作用。 */
    private static String observedEffect(RecordingRoundSink sink, TurnRunState runState) {
        if (sink.finalizeNormalCalls > 0) {
            return "finalize";
        }
        if (sink.enterWaitingConfirmCalls > 0) {
            return "waiting";
        }
        if (!sink.persisted.isEmpty()) {
            return sink.persisted.get(0).type().value();
        }
        if (!sink.frames.isEmpty()) {
            return "frame:" + sink.frameTargets.get(0).value();
        }
        return "none";
    }

    /** 各信号类型的属主策略预期效果（拆分前 switch 逐 case 可观测语义）。 */
    private static String expectedEffect(AgentStreamSignalType type) {
        return switch (type) {
            case TEXT_DELTA -> "frame:agent.message";
            case TEXT_END -> "agent.message";
            case THINKING_DELTA -> "frame:agent.thinking";
            case THINKING_END -> "agent.thinking";
            case TOOL_CALL_START, TOOL_CALL_DELTA, AGENT_RESULT, EXCEED_MAX_ITERS, HUMAN_CONFIRM_RESULT -> "none";
            case TOOL_CALL_END -> "agent.tool_use";
            case TOOL_RESULT_TEXT_DELTA -> "none";
            case TOOL_RESULT_END -> "agent.tool_result";
            case MODEL_CALL_START -> "span.model_request_start";
            case MODEL_CALL_END -> "span.model_request_end";
            case AGENT_END -> "finalize";
            case HUMAN_CONFIRM_REQUIRED -> "waiting";
        };
    }

    private static SignalContext newContext(AgentStreamSignal signal, TurnRunState runState,
                                            RecordingRoundSink sink) {
        AgentSessionContext sessionContext = mock(AgentSessionContext.class);
        ConnectionHandle connection = mock(ConnectionHandle.class);
        when(sessionContext.sessionId()).thenReturn("sess-1");
        when(sessionContext.currentSequence()).thenReturn(7L);
        when(sessionContext.connection()).thenReturn(connection);
        when(connection.deltaFlushIntervalMs()).thenReturn(60_000L);
        return new SignalContext(signal, sessionContext, runState, sink);
    }
}

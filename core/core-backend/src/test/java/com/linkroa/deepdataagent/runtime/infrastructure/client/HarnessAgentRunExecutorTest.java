package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HarnessAgentRunExecutor} 事件映射单测：验证 SDK 事件流到领域中性信号的纯映射
 * （无状态累积、无 blockLast 阻塞、无持久化）。
 */
class HarnessAgentRunExecutorTest {

    @Test
    void should_mapAllSdkEventsToSignals_when_streamEvents_given_fullEventStream() {
        // given
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);
        when(harness.getModel()).thenReturn(null);
        when(harness.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.just(
                new TextBlockDeltaEvent("r1", "blk-1", "你好"),
                new TextBlockEndEvent("r1", "blk-1"),
                new ThinkingBlockDeltaEvent("r1", "th-1", "思考"),
                new ToolCallStartEvent("r1", "tc-1", "search"),
                new ToolCallDeltaEvent("r1", "tc-1", "search", "{\"q\""),
                new ToolCallEndEvent("r1", "tc-1", "search"),
                new ToolResultTextDeltaEvent("r1", "tc-1", "search", "R1"),
                new ToolResultEndEvent("r1", "tc-1", "search", ToolResultState.SUCCESS),
                new ModelCallStartEvent("r1"),
                new ModelCallEndEvent("r1", new ChatUsage(100, 50, 0.0)),
                new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("最终答案").build()),
                new AgentEndEvent("r1"),
                new ExceedMaxItersEvent("r1", 10, 10),
                // 无映射语义的 AGENT_START / 块 Start / 自定义事件：应被 filter 丢弃
                new AgentStartEvent("s-1", "r1", "agent-a"),
                new ThinkingBlockStartEvent("r1", "th-0"),
                new CustomEvent("custom-event")));
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();

        // when
        List<AgentStreamSignal> signals = executor.streamEvents(agent, "你好", "s-1", "u-1")
                .collectList().block();

        // then（13 个有语义事件全部映射；AGENT_START 与两个块 Start / 自定义事件均无映射语义，被 filter 丢弃）
        assertNotNull(signals);
        assertEquals(13, signals.size());
        assertEquals(AgentStreamSignalType.TEXT_DELTA, signals.get(0).type());
        assertEquals("你好", signals.get(0).text());
        assertEquals("blk-1", signals.get(0).blockId());
        assertEquals(AgentStreamSignalType.TEXT_END, signals.get(1).type());
        assertEquals(AgentStreamSignalType.THINKING_DELTA, signals.get(2).type());
        assertEquals("思考", signals.get(2).text());
        assertEquals(AgentStreamSignalType.TOOL_CALL_START, signals.get(3).type());
        assertEquals("tc-1", signals.get(3).toolCallId());
        assertEquals("search", signals.get(3).toolName());
        assertEquals(AgentStreamSignalType.TOOL_CALL_DELTA, signals.get(4).type());
        assertEquals("{\"q\"", signals.get(4).text());
        assertEquals(AgentStreamSignalType.TOOL_CALL_END, signals.get(5).type());
        assertEquals(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, signals.get(6).type());
        assertEquals("R1", signals.get(6).text());
        assertEquals(AgentStreamSignalType.TOOL_RESULT_END, signals.get(7).type());
        assertNotNull(signals.get(7).toolState(), "工具结果状态应以协议字符串透传");
        assertEquals(AgentStreamSignalType.MODEL_CALL_START, signals.get(8).type());
        assertEquals(AgentStreamSignalType.MODEL_CALL_END, signals.get(9).type());
        assertEquals(100, signals.get(9).inputTokens());
        assertEquals(50, signals.get(9).outputTokens());
        assertEquals(AgentStreamSignalType.AGENT_RESULT, signals.get(10).type());
        assertEquals("最终答案", signals.get(10).resultText());
        assertEquals(AgentStreamSignalType.AGENT_END, signals.get(11).type());
        assertEquals(AgentStreamSignalType.EXCEED_MAX_ITERS, signals.get(12).type());

        // then：映射为冷流，订阅后才触发 SDK 事件流
        verify(harness).streamEvents(any(Msg.class), any(RuntimeContext.class));
    }

    @Test
    void should_throw_when_streamEvents_given_unsupportedAgentHandle() {
        // given
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();
        com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent unsupported =
                mock(com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent.class);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> executor.streamEvents(unsupported, "你好", "s-1", "u-1"));
    }

    @Test
    void should_mapHitlEventsToSignals_when_streamEvents_given_requireAndConfirmEvents() {
        // given
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);
        when(harness.getModel()).thenReturn(null);

        ToolUseBlock firstCall = mock(ToolUseBlock.class);
        when(firstCall.getId()).thenReturn("tc-1");
        ToolUseBlock secondCall = mock(ToolUseBlock.class);
        when(secondCall.getId()).thenReturn("tc-2");
        RequireUserConfirmEvent requireConfirm = mock(RequireUserConfirmEvent.class);
        when(requireConfirm.getType()).thenReturn(AgentEventType.REQUIRE_USER_CONFIRM);
        when(requireConfirm.getReplyId()).thenReturn("reply-1");
        when(requireConfirm.getToolCalls()).thenReturn(List.of(firstCall, secondCall));

        RequireExternalExecutionEvent requireExternal = mock(RequireExternalExecutionEvent.class);
        when(requireExternal.getType()).thenReturn(AgentEventType.REQUIRE_EXTERNAL_EXECUTION);
        when(requireExternal.getReplyId()).thenReturn("reply-2");
        when(requireExternal.getToolCalls()).thenReturn(List.of(firstCall));

        UserConfirmResultEvent confirmResult = mock(UserConfirmResultEvent.class);
        when(confirmResult.getType()).thenReturn(AgentEventType.USER_CONFIRM_RESULT);
        when(confirmResult.getReplyId()).thenReturn("reply-3");

        when(harness.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(requireConfirm, requireExternal, confirmResult));
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();

        // when
        List<AgentStreamSignal> signals = executor.streamEvents(agent, "你好", "s-1", "u-1")
                .collectList().block();

        // then（REQUIRE_* 按 reply 整批透传待确认工具调用 id，挂起明细账本锚点由应用层建立）
        assertNotNull(signals);
        assertEquals(3, signals.size());
        assertEquals(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, signals.get(0).type());
        assertEquals("reply-1", signals.get(0).replyId());
        assertEquals(List.of("tc-1", "tc-2"), signals.get(0).toolCallIds());
        assertEquals(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, signals.get(1).type());
        assertEquals("reply-2", signals.get(1).replyId());
        assertEquals(List.of("tc-1"), signals.get(1).toolCallIds());
        assertEquals(AgentStreamSignalType.HUMAN_CONFIRM_RESULT, signals.get(2).type());
        assertEquals("reply-3", signals.get(2).replyId());
    }

    @Test
    void should_resumeWholeBatch_when_resumeConfirmation_given_rebuiltLedgerSpecs() {
        // given：无任何进程内暂存，仅账本重建的整批明细（两个待确认工具调用）
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);
        when(harness.getModel()).thenReturn(null);

        UserConfirmResultEvent confirmResult = mock(UserConfirmResultEvent.class);
        when(confirmResult.getType()).thenReturn(AgentEventType.USER_CONFIRM_RESULT);
        when(confirmResult.getReplyId()).thenReturn("reply-1");
        when(harness.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.just(confirmResult));
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();
        List<PendingToolCallSpec> specs = List.of(
                new PendingToolCallSpec("tc-1", "search", "{\"q\":\"x\"}"),
                new PendingToolCallSpec("tc-2", "query_db", "{\"sql\":\"select 1\"}"));

        // when：durable 续跑（allow 整批裁决）
        List<AgentStreamSignal> resumed =
                executor.resumeConfirmation(agent, specs, "s-1", "u-1", true, null).collectList().block();

        // then：续流输入消息携带 agentscope_confirm_results，整批明细逐一重建为工具调用块
        ArgumentCaptor<Msg> msgCaptor = ArgumentCaptor.forClass(Msg.class);
        verify(harness).streamEvents(msgCaptor.capture(), any(RuntimeContext.class));
        Msg confirmMsg = msgCaptor.getValue();
        assertEquals(MsgRole.USER, confirmMsg.getRole());
        List<?> results = (List<?>) confirmMsg.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertNotNull(results);
        assertEquals(2, results.size());
        ConfirmResult first = (ConfirmResult) results.get(0);
        assertTrue(first.isConfirmed());
        assertEquals("tc-1", first.getToolCall().getId());
        assertEquals("search", first.getToolCall().getName());
        assertEquals("x", first.getToolCall().getInput().get("q"));
        ConfirmResult second = (ConfirmResult) results.get(1);
        assertEquals("tc-2", second.getToolCall().getId());
        assertEquals("select 1", second.getToolCall().getInput().get("sql"));
        // then：续流事件透传为 HUMAN_CONFIRM_RESULT
        assertNotNull(resumed);
        assertEquals(1, resumed.size());
        assertEquals(AgentStreamSignalType.HUMAN_CONFIRM_RESULT, resumed.get(0).type());
    }

    @Test
    void should_injectDenyWithMessage_when_resumeConfirmation_given_denyDecision() {
        // given
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);
        when(harness.getModel()).thenReturn(null);
        when(harness.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();

        // when：拒绝并携带拒绝说明
        executor.resumeConfirmation(agent, List.of(new PendingToolCallSpec("tc-1", "search", "{}")),
                "s-1", "u-1", false, "该删除操作不允许").collectList().block();

        // then：确认结果为拒绝且拒绝说明以用户消息文本携带（模型据此调整后续行为）
        ArgumentCaptor<Msg> msgCaptor = ArgumentCaptor.forClass(Msg.class);
        verify(harness).streamEvents(msgCaptor.capture(), any(RuntimeContext.class));
        Msg confirmMsg = msgCaptor.getValue();
        List<?> results = (List<?>) confirmMsg.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertNotNull(results);
        assertEquals(1, results.size());
        ConfirmResult result = (ConfirmResult) results.get(0);
        assertFalse(result.isConfirmed());
        assertEquals("tc-1", result.getToolCall().getId());
        assertEquals("该删除操作不允许", confirmMsg.getTextContent());
    }

    @Test
    void should_emitError_when_resumeConfirmation_given_emptySpecBatch() {
        // given
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();
        HarnessBuiltAgent agent = new HarnessBuiltAgent(mock(HarnessAgent.class));

        // when & then：空批次属编程错误（账本明细缺失），流内直接报错不外呼 SDK
        assertThrows(IllegalArgumentException.class,
                () -> executor.resumeConfirmation(agent, List.of(), "s-1", "u-1", true, null).blockFirst());
    }
}
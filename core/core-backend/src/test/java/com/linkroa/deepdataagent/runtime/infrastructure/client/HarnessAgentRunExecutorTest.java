package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        HarnessBuiltAgent agent = new HarnessBuiltAgent("agent-a", harness);
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
                new AgentStartEvent("s-1", "r1", "agent-a"),
                new AgentEndEvent("r1"),
                new ExceedMaxItersEvent("r1", 10, 10),
                // 无映射语义的块 Start / 自定义事件：应被 filter 丢弃
                new ThinkingBlockStartEvent("r1", "th-0"),
                new CustomEvent("custom-event")));
        HarnessAgentRunExecutor executor = new HarnessAgentRunExecutor();

        // when
        List<AgentStreamSignal> signals = executor.streamEvents(agent, "你好", "s-1", "u-1")
                .collectList().block();

        // then（14 个有语义事件全部映射，2 个无语义事件被过滤）
        assertNotNull(signals);
        assertEquals(14, signals.size());
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
        assertEquals(AgentStreamSignalType.START, signals.get(11).type());
        assertEquals(AgentStreamSignalType.AGENT_END, signals.get(12).type());
        assertEquals(AgentStreamSignalType.EXCEED_MAX_ITERS, signals.get(13).type());

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
}
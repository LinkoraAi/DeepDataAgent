package com.linkroa.deepdataagent.agent.application.adapter;

import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EventAdapter 单元测试
 * <p>覆盖 SSE 事件到 {@link DialogueMessage} 的映射（THINKING/TOOL_CALL/TOOL_RESULT/MESSAGE/ERROR）、
 * TEXT_DELTA 不入库、序号自增（复用 sequenceNumberCounter）、最终 ASSISTANT 消息来自 assistantTextBuilder。</p>
 */
@ExtendWith(MockitoExtension.class)
class EventAdapterTest {

    private EventAdapter eventAdapter;

    @BeforeEach
    void setUp() {
        eventAdapter = new EventAdapter();
    }

    @Test
    void should_appendThinkingMessage_when_handleEvent_given_thinkingEnd() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ThinkingBlockDeltaEvent delta = mock(ThinkingBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("思考过程");
        eventAdapter.handleEvent("session-1", delta);
        ThinkingBlockEndEvent end = mock(ThinkingBlockEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.THINKING_BLOCK_END);

        // when
        eventAdapter.handleEvent("session-1", end);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 首条为用户消息（seq=1），第二条为思考消息
        assertEquals(2, messages.size());
        assertEquals(MessageRole.USER, messages.get(0).getRole());
        assertEquals(MessageRole.THINKING, messages.get(1).getRole());
        assertEquals(MessageType.THINKING, messages.get(1).getMessageType());
        assertEquals("思考过程", messages.get(1).getContent().result());
    }

    @Test
    void should_appendToolCallMessage_when_handleEvent_given_toolCallEnd() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ToolCallStartEvent start = mock(ToolCallStartEvent.class);
        when(start.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        when(start.getToolCallName()).thenReturn("generate_sql");
        when(start.getToolCallId()).thenReturn("tc1");
        eventAdapter.handleEvent("session-1", start);
        ToolCallDeltaEvent delta = mock(ToolCallDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_CALL_DELTA);
        when(delta.getToolCallId()).thenReturn("tc1");
        when(delta.getDelta()).thenReturn("{\"sql\":\"SELECT 1\"}");
        eventAdapter.handleEvent("session-1", delta);
        ToolCallEndEvent end = mock(ToolCallEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.TOOL_CALL_END);
        when(end.getToolCallId()).thenReturn("tc1");

        // when
        eventAdapter.handleEvent("session-1", end);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 首条为用户消息（seq=1），第二条为工具调用消息
        assertEquals(2, messages.size());
        assertEquals(MessageRole.TOOL, messages.get(1).getRole());
        assertEquals(MessageType.TOOL_CALL, messages.get(1).getMessageType());
        assertEquals("generate_sql", messages.get(1).getContent().title());
    }

    @Test
    void should_mergeToolResultIntoToolCall_when_handleEvent_given_fullToolSequence() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ToolCallStartEvent start = mock(ToolCallStartEvent.class);
        when(start.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        when(start.getToolCallName()).thenReturn("generate_sql");
        when(start.getToolCallId()).thenReturn("tc1");
        eventAdapter.handleEvent("session-1", start);
        ToolCallDeltaEvent callDelta = mock(ToolCallDeltaEvent.class);
        when(callDelta.getType()).thenReturn(AgentEventType.TOOL_CALL_DELTA);
        when(callDelta.getToolCallId()).thenReturn("tc1");
        when(callDelta.getDelta()).thenReturn("{\"sql\":\"SELECT 1\"}");
        eventAdapter.handleEvent("session-1", callDelta);
        ToolCallEndEvent callEnd = mock(ToolCallEndEvent.class);
        when(callEnd.getType()).thenReturn(AgentEventType.TOOL_CALL_END);
        when(callEnd.getToolCallId()).thenReturn("tc1");
        eventAdapter.handleEvent("session-1", callEnd);
        ToolResultTextDeltaEvent delta = mock(ToolResultTextDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA);
        when(delta.getToolCallId()).thenReturn("tc1");
        when(delta.getDelta()).thenReturn("结果数据");
        eventAdapter.handleEvent("session-1", delta);
        ToolResultEndEvent end = mock(ToolResultEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(end.getToolCallId()).thenReturn("tc1");
        when(end.getState()).thenReturn(ToolResultState.SUCCESS);

        // when
        eventAdapter.handleEvent("session-1", end);

        // then
        // 首条为用户消息（seq=1），第二条为合并后的工具调用消息（入参与结果一体）
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        DialogueMessage merged = messages.get(1);
        assertEquals(MessageRole.TOOL, merged.getRole());
        assertEquals(MessageType.TOOL_CALL, merged.getMessageType());
        assertEquals("generate_sql", merged.getContent().title());
        assertEquals("{\"sql\":\"SELECT 1\"}", merged.getContent().input());
        assertEquals("结果数据", merged.getContent().result());
    }

    @Test
    void should_mergeSameNameToolsByToolCallId_when_handleEvent_given_multipleSameNameCalls() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        // 同名工具 generate_sql 被调用两次，toolCallId 分别为 tc1 与 tc2
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");
        simulateToolCall("tc2", "generate_sql", "{\"sql\":\"SELECT 2\"}");

        // when
        // 结果按各自 toolCallId 回填，顺序不打乱配对
        simulateToolResult("tc2", "结果B", ToolResultState.SUCCESS);
        simulateToolResult("tc1", "结果A", ToolResultState.SUCCESS);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 用户消息 + 两次工具调用
        assertEquals(3, messages.size());
        DialogueMessage call1 = messages.get(1);
        DialogueMessage call2 = messages.get(2);
        assertEquals("{\"sql\":\"SELECT 1\"}", call1.getContent().input());
        assertEquals("结果A", call1.getContent().result());
        assertEquals("{\"sql\":\"SELECT 2\"}", call2.getContent().input());
        assertEquals("结果B", call2.getContent().result());
    }

    @Test
    void should_keepInputOnly_when_handleEvent_given_toolCallEndWithoutResult() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");

        // when
        // 无 TOOL_RESULT_END（分析中断）

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        DialogueMessage interrupted = messages.get(1);
        assertEquals(MessageType.TOOL_CALL, interrupted.getMessageType());
        assertEquals("{\"sql\":\"SELECT 1\"}", interrupted.getContent().input());
        assertEquals("", interrupted.getContent().result());
    }

    /** 模拟一次完整的工具调用（START → DELTA → END），登记 toolCallId 映射 */
    private void simulateToolCall(String toolCallId, String toolName, String input) {
        ToolCallStartEvent start = mock(ToolCallStartEvent.class);
        when(start.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        when(start.getToolCallName()).thenReturn(toolName);
        when(start.getToolCallId()).thenReturn(toolCallId);
        eventAdapter.handleEvent("session-1", start);
        ToolCallDeltaEvent delta = mock(ToolCallDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_CALL_DELTA);
        when(delta.getToolCallId()).thenReturn(toolCallId);
        when(delta.getDelta()).thenReturn(input);
        eventAdapter.handleEvent("session-1", delta);
        ToolCallEndEvent end = mock(ToolCallEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.TOOL_CALL_END);
        when(end.getToolCallId()).thenReturn(toolCallId);
        eventAdapter.handleEvent("session-1", end);
    }

    /** 模拟一次工具结果返回并回填 */
    private void simulateToolResult(String toolCallId, String result, ToolResultState state) {
        ToolResultTextDeltaEvent delta = mock(ToolResultTextDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA);
        when(delta.getToolCallId()).thenReturn(toolCallId);
        when(delta.getDelta()).thenReturn(result);
        eventAdapter.handleEvent("session-1", delta);
        ToolResultEndEvent end = mock(ToolResultEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(end.getToolCallId()).thenReturn(toolCallId);
        when(end.getState()).thenReturn(state);
        eventAdapter.handleEvent("session-1", end);
    }

    @Test
    void should_notPersistTextDelta_when_handleEvent_given_textBlockDelta() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("完整报告");

        // when
        eventAdapter.handleEvent("session-1", delta);

        // then
        // 仅用户消息（seq=1）在列表中，TEXT_DELTA 不入库
        assertEquals(1, eventAdapter.getContext("session-1").getMessages().size());
        assertEquals(MessageRole.USER, eventAdapter.getContext("session-1").getMessages().get(0).getRole());
        assertEquals("完整报告", eventAdapter.getContext("session-1").getAssistantText());
    }

    @Test
    void should_appendAssistantMessage_when_handleEvent_given_agentEnd() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("分析结论");
        eventAdapter.handleEvent("session-1", delta);
        AgentEvent end = mock(AgentEvent.class);
        when(end.getType()).thenReturn(AgentEventType.AGENT_END);

        // when
        eventAdapter.handleEvent("session-1", end);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 首条为用户消息（seq=1），第二条为最终助手消息
        assertEquals(2, messages.size());
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals(MessageType.MESSAGE, messages.get(1).getMessageType());
        assertEquals("分析结论", messages.get(1).getContent().result());
    }

    @Test
    void should_flushNarrativeIntoThinking_when_handleEvent_given_toolCallStartAfterTextBlock() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("SQL执行失败，我来修正");
        eventAdapter.handleEvent("session-1", delta);
        ToolCallStartEvent start = mock(ToolCallStartEvent.class);
        when(start.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        when(start.getToolCallName()).thenReturn("execute_sql");
        when(start.getToolCallId()).thenReturn("tc1");

        // when
        eventAdapter.handleEvent("session-1", start);

        // then
        // 中间叙述作为 THINKING 消息落库，且缓冲被清空
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        assertEquals(MessageRole.THINKING, messages.get(1).getRole());
        assertEquals(MessageType.THINKING, messages.get(1).getMessageType());
        assertEquals("SQL执行失败，我来修正", messages.get(1).getContent().result());
        assertNull(eventAdapter.getContext("session-1").flushAssistantText());
    }

    @Test
    void should_preferFinalResponseOverTextBlock_when_handleEvent_given_agentResultAndAgentEnd() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("最终报告的流水文本");
        eventAdapter.handleEvent("session-1", delta);
        Msg result = mock(Msg.class);
        when(result.getTextContent()).thenReturn("## channel_account 表数据分析报告");
        AgentResultEvent agentResult = mock(AgentResultEvent.class);
        when(agentResult.getType()).thenReturn(AgentEventType.AGENT_RESULT);
        when(agentResult.getResult()).thenReturn(result);
        eventAdapter.handleEvent("session-1", agentResult);
        AgentEvent end = mock(AgentEvent.class);
        when(end.getType()).thenReturn(AgentEventType.AGENT_END);

        // when
        eventAdapter.handleEvent("session-1", end);

        // then
        // 最终 ASSISTANT 消息优先采用 AGENT_RESULT 的权威最终文本，而非累积文本
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals(MessageType.MESSAGE, messages.get(1).getMessageType());
        assertEquals("## channel_account 表数据分析报告", messages.get(1).getContent().result());
    }

    @Test
    void should_appendErrorMessage_when_addError_given_registeredContext() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");

        // when
        eventAdapter.addError("session-1", "执行失败");

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 首条为用户消息（seq=1），第二条为错误消息
        assertEquals(2, messages.size());
        assertEquals(MessageType.ERROR, messages.get(1).getMessageType());
        assertEquals("执行失败", messages.get(1).getContent().result());
    }

    @Test
    void should_incrementSequenceNumber_when_handleEvents_given_multipleEvents() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ThinkingBlockDeltaEvent delta = mock(ThinkingBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("思考");
        eventAdapter.handleEvent("session-1", delta);
        ThinkingBlockEndEvent end = mock(ThinkingBlockEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.THINKING_BLOCK_END);
        eventAdapter.handleEvent("session-1", end);

        // when
        // 第二条消息（TEXT_DELTA 不入库，AGENT_END 产生第二条）
        TextBlockDeltaEvent textDelta = mock(TextBlockDeltaEvent.class);
        when(textDelta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(textDelta.getDelta()).thenReturn("结论");
        eventAdapter.handleEvent("session-1", textDelta);
        AgentEvent agentEnd = mock(AgentEvent.class);
        when(agentEnd.getType()).thenReturn(AgentEventType.AGENT_END);
        eventAdapter.handleEvent("session-1", agentEnd);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 用户消息(seq=1) + 思考(seq=2) + 助手(seq=3)
        assertEquals(3, messages.size());
        assertEquals(1L, messages.get(0).getSequenceNumber());
        assertEquals(2L, messages.get(1).getSequenceNumber());
        assertEquals(3L, messages.get(2).getSequenceNumber());
    }

    @Test
    void should_setFinalResponse_when_handleEvent_given_agentResult() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        Msg result = mock(Msg.class);
        when(result.getTextContent()).thenReturn("最终回复");
        AgentResultEvent agentResult = mock(AgentResultEvent.class);
        when(agentResult.getType()).thenReturn(AgentEventType.AGENT_RESULT);
        when(agentResult.getResult()).thenReturn(result);

        // when
        eventAdapter.handleEvent("session-1", agentResult);

        // then
        assertEquals("最终回复", eventAdapter.getContext("session-1").finalResponse());
    }

    @Test
    void should_unregisterContext_when_unregisterContext_given_sessionId() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");

        // when
        eventAdapter.unregisterContext("session-1");

        // then
        assertNull(eventAdapter.getContext("session-1"));
    }

    @Test
    void should_returnRegisteredContext_when_getContext_given_registeredSessionId() {
        // given
        EventAdapter.CollectorContext context = eventAdapter.registerContext("session-1", "分析销量");

        // when
        EventAdapter.CollectorContext retrieved = eventAdapter.getContext("session-1");

        // then
        assertSame(context, retrieved);
        assertEquals("session-1", retrieved.sessionId());
    }
}
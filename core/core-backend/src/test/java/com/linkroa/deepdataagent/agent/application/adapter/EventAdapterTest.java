package com.linkroa.deepdataagent.agent.application.adapter;

import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus;
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
 * DELTA 事件实时创建/更新进行中消息（IN_PROGRESS）、块结束收敛为 COMPLETED/FAILED、
 * {@link EventAdapter#handleEvent} 返回值驱动逐事件落库、序号自增。</p>
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
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
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
    void should_splitToolCallAndResult_when_handleEvent_given_fullToolSequence() {
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
        // 首条为用户消息（seq=1），随后为 TOOL_CALL（入参）与 TOOL_RESULT（结果）两条独立消息
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(3, messages.size());
        DialogueMessage callMessage = messages.get(1);
        assertEquals(MessageRole.TOOL, callMessage.getRole());
        assertEquals(MessageType.TOOL_CALL, callMessage.getMessageType());
        assertEquals(MessageStatus.COMPLETED, callMessage.getStatus());
        assertEquals("generate_sql", callMessage.getContent().title());
        assertEquals("{\"sql\":\"SELECT 1\"}", callMessage.getContent().input());
        assertEquals("", callMessage.getContent().result());
        // 调用消息携带 toolCallId，与结果消息一致，供前端精确配对
        assertEquals("tc1", callMessage.getContent().toolCallId());
        DialogueMessage resultMessage = messages.get(2);
        assertEquals(MessageRole.TOOL, resultMessage.getRole());
        assertEquals(MessageType.TOOL_RESULT, resultMessage.getMessageType());
        assertEquals(MessageStatus.COMPLETED, resultMessage.getStatus());
        assertEquals("generate_sql", resultMessage.getContent().title());
        assertEquals("结果数据", resultMessage.getContent().result());
        assertEquals("tc1", resultMessage.getContent().toolCallId());
    }

    @Test
    void should_splitSameNameToolsByToolCallId_when_handleEvent_given_multipleSameNameCalls() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        // 同名工具 generate_sql 被调用两次，toolCallId 分别为 tc1 与 tc2
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");
        simulateToolCall("tc2", "generate_sql", "{\"sql\":\"SELECT 2\"}");

        // when
        // 结果按各自 toolCallId 生成独立 TOOL_RESULT 消息，顺序不打乱配对
        simulateToolResult("tc2", "结果B", ToolResultState.SUCCESS);
        simulateToolResult("tc1", "结果A", ToolResultState.SUCCESS);

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        // 用户消息 + 两次工具调用（TOOL_CALL×2 + TOOL_RESULT×2）
        assertEquals(5, messages.size());
        assertEquals("{\"sql\":\"SELECT 1\"}", messages.get(1).getContent().input());
        assertEquals("", messages.get(1).getContent().result());
        assertEquals("tc1", messages.get(1).getContent().toolCallId());
        assertEquals("{\"sql\":\"SELECT 2\"}", messages.get(2).getContent().input());
        assertEquals("", messages.get(2).getContent().result());
        assertEquals("tc2", messages.get(2).getContent().toolCallId());
        // tc2 的结果消息（先返回），toolCallId 与 tc2 调用一致
        assertEquals(MessageType.TOOL_RESULT, messages.get(3).getMessageType());
        assertEquals("generate_sql", messages.get(3).getContent().title());
        assertEquals("结果B", messages.get(3).getContent().result());
        assertEquals("tc2", messages.get(3).getContent().toolCallId());
        // tc1 的结果消息（后返回），toolCallId 与 tc1 调用一致（乱序到达仍按 toolCallId 精确配对）
        assertEquals(MessageType.TOOL_RESULT, messages.get(4).getMessageType());
        assertEquals("generate_sql", messages.get(4).getContent().title());
        assertEquals("结果A", messages.get(4).getContent().result());
        assertEquals("tc1", messages.get(4).getContent().toolCallId());
    }

    @Test
    void should_keepInputOnly_when_handleEvent_given_toolCallEndWithoutResult() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");

        // when
        // 无 TOOL_RESULT_END（分析中断），仅产生 TOOL_CALL 消息、无独立结果消息

        // then
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        DialogueMessage interrupted = messages.get(1);
        assertEquals(MessageType.TOOL_CALL, interrupted.getMessageType());
        assertEquals(MessageStatus.COMPLETED, interrupted.getStatus());
        assertEquals("{\"sql\":\"SELECT 1\"}", interrupted.getContent().input());
        assertEquals("", interrupted.getContent().result());
        // 中断场景调用消息仍携带 toolCallId
        assertEquals("tc1", interrupted.getContent().toolCallId());
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
    void should_createInProgressAssistantMessage_when_handleEvent_given_textBlockDelta() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("完整报告");

        // when
        List<DialogueMessage> affected = eventAdapter.handleEvent("session-1", delta);

        // then
        // TEXT_DELTA 实时创建进行中报告消息并返回，供调用方逐事件落库
        assertEquals(1, affected.size());
        assertEquals(MessageStatus.IN_PROGRESS, affected.get(0).getStatus());
        assertEquals(MessageRole.ASSISTANT, affected.get(0).getRole());
        assertEquals(MessageType.MESSAGE, affected.get(0).getMessageType());
        // 进行中消息对象仅占位，落库快照从收集器注入当前累积文本
        List<DialogueMessage> snapshot = eventAdapter.getContext("session-1").getPersistenceSnapshot();
        assertEquals("完整报告", snapshot.get(1).getContent().result());
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        assertEquals(MessageStatus.IN_PROGRESS, messages.get(1).getStatus());
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
        List<DialogueMessage> affected = eventAdapter.handleEvent("session-1", start);

        // then
        // 中间叙述转为 THINKING 消息收敛，同时新建进行中的 TOOL_CALL 消息
        assertEquals(2, affected.size());
        assertEquals(MessageRole.ASSISTANT, affected.get(0).getRole());
        assertEquals(MessageType.THINKING, affected.get(0).getMessageType());
        assertEquals(MessageStatus.COMPLETED, affected.get(0).getStatus());
        assertEquals("SQL执行失败，我来修正", affected.get(0).getContent().result());
        assertEquals(MessageRole.TOOL, affected.get(1).getRole());
        assertEquals(MessageStatus.IN_PROGRESS, affected.get(1).getStatus());
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(3, messages.size());
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
    void should_accumulateThinkingDeltaIntoSameMessage_when_handleEvent_given_multipleThinkingDeltas() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ThinkingBlockDeltaEvent delta1 = mock(ThinkingBlockDeltaEvent.class);
        when(delta1.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta1.getDelta()).thenReturn("第一步");
        eventAdapter.handleEvent("session-1", delta1);
        ThinkingBlockDeltaEvent delta2 = mock(ThinkingBlockDeltaEvent.class);
        when(delta2.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta2.getDelta()).thenReturn("第二步");
        eventAdapter.handleEvent("session-1", delta2);

        // when
        ThinkingBlockEndEvent end = mock(ThinkingBlockEndEvent.class);
        when(end.getType()).thenReturn(AgentEventType.THINKING_BLOCK_END);
        List<DialogueMessage> affected = eventAdapter.handleEvent("session-1", end);

        // then
        // 多个 DELTA 复用同一条进行中消息，增量内容实时累积；END 收敛为 COMPLETED
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();
        assertEquals(2, messages.size());
        assertEquals("第一步第二步", messages.get(1).getContent().result());
        assertEquals(1, affected.size());
        assertEquals(MessageStatus.COMPLETED, affected.get(0).getStatus());
        assertEquals("第一步第二步", affected.get(0).getContent().result());
    }

    @Test
    void should_markToolResultFailed_when_handleEvent_given_toolResultEndWithErrorState() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        simulateToolCall("tc1", "execute_sql", "{\"sql\":\"SELECT * FROM t\"}");
        simulateToolResult("tc1", "SQL 语法错误", ToolResultState.ERROR);

        // when
        // 工具结果以 ERROR 状态返回，独立的 TOOL_RESULT 消息收敛为 FAILED，TOOL_CALL 保持 COMPLETED
        List<DialogueMessage> messages = eventAdapter.getContext("session-1").getMessages();

        // then
        assertEquals(3, messages.size());
        DialogueMessage callMessage = messages.get(1);
        assertEquals(MessageType.TOOL_CALL, callMessage.getMessageType());
        assertEquals(MessageStatus.COMPLETED, callMessage.getStatus());
        assertEquals("", callMessage.getContent().result());
        DialogueMessage resultMessage = messages.get(2);
        assertEquals(MessageType.TOOL_RESULT, resultMessage.getMessageType());
        assertEquals(MessageStatus.FAILED, resultMessage.getStatus());
        assertEquals("execute_sql", resultMessage.getContent().title());
        assertEquals("SQL 语法错误", resultMessage.getContent().result());
    }

    @Test
    void should_returnEmpty_when_handleEvent_given_agentResult() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        Msg result = mock(Msg.class);
        when(result.getTextContent()).thenReturn("最终回复");
        AgentResultEvent agentResult = mock(AgentResultEvent.class);
        when(agentResult.getType()).thenReturn(AgentEventType.AGENT_RESULT);
        when(agentResult.getResult()).thenReturn(result);

        // when
        List<DialogueMessage> affected = eventAdapter.handleEvent("session-1", agentResult);

        // then
        // AGENT_RESULT 仅记录权威最终文本，不产生消息变化，无需落库
        assertEquals(0, affected.size());
        assertEquals("最终回复", eventAdapter.getContext("session-1").finalResponse());
    }

    @Test
    void should_createToolResultMessage_when_handleEvent_given_toolResultTextDelta() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");
        ToolResultTextDeltaEvent delta = mock(ToolResultTextDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA);
        when(delta.getToolCallId()).thenReturn("tc1");
        when(delta.getDelta()).thenReturn("结果A");

        // when
        List<DialogueMessage> affected = eventAdapter.handleEvent("session-1", delta);

        // then
        // 结果增量首次到达时惰性创建独立的 TOOL_RESULT 消息并实时返回；落库快照从收集器注入结果累积文本
        assertEquals(1, affected.size());
        assertEquals(MessageType.TOOL_RESULT, affected.get(0).getMessageType());
        assertEquals(MessageStatus.IN_PROGRESS, affected.get(0).getStatus());
        assertEquals("generate_sql", affected.get(0).getContent().title());
        List<DialogueMessage> snapshot = eventAdapter.getContext("session-1").getPersistenceSnapshot();
        assertEquals("generate_sql", snapshot.get(1).getContent().title());
        assertEquals("{\"sql\":\"SELECT 1\"}", snapshot.get(1).getContent().input());
        assertEquals("", snapshot.get(1).getContent().result());
        // 结果消息为快照的最后一条，注入当前累积结果
        DialogueMessage resultSnapshot = snapshot.get(snapshot.size() - 1);
        assertEquals(MessageType.TOOL_RESULT, resultSnapshot.getMessageType());
        assertEquals("结果A", resultSnapshot.getContent().result());
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
    void should_incrementMessageNumber_when_handleEvents_given_multipleEvents() {
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
        assertEquals(1L, messages.get(0).getMessageNumber());
        assertEquals(2L, messages.get(1).getMessageNumber());
        assertEquals(3L, messages.get(2).getMessageNumber());
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

    @Test
    void should_injectAccumulatedThinking_when_getPersistenceSnapshot_given_thinkingDeltas() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        ThinkingBlockDeltaEvent delta1 = mock(ThinkingBlockDeltaEvent.class);
        when(delta1.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta1.getDelta()).thenReturn("第一步");
        eventAdapter.handleEvent("session-1", delta1);
        ThinkingBlockDeltaEvent delta2 = mock(ThinkingBlockDeltaEvent.class);
        when(delta2.getType()).thenReturn(AgentEventType.THINKING_BLOCK_DELTA);
        when(delta2.getDelta()).thenReturn("第二步");
        eventAdapter.handleEvent("session-1", delta2);

        // when
        List<DialogueMessage> snapshot = eventAdapter.getContext("session-1").getPersistenceSnapshot();

        // then
        // 落库快照从收集器注入当前累积文本，而内存中的进行中消息仅占位（消除双份持有）
        assertEquals("第一步第二步", snapshot.get(1).getContent().result());
        assertEquals("", eventAdapter.getContext("session-1").getMessages().get(1).getContent().result());
    }

    @Test
    void should_injectAccumulatedToolContent_when_getPersistenceSnapshot_given_toolDeltas() {
        // given
        eventAdapter.registerContext("session-1", "分析销量");
        simulateToolCall("tc1", "generate_sql", "{\"sql\":\"SELECT 1\"}");
        ToolResultTextDeltaEvent delta = mock(ToolResultTextDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA);
        when(delta.getToolCallId()).thenReturn("tc1");
        when(delta.getDelta()).thenReturn("第一段");
        eventAdapter.handleEvent("session-1", delta);
        ToolResultTextDeltaEvent delta2 = mock(ToolResultTextDeltaEvent.class);
        when(delta2.getType()).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA);
        when(delta2.getToolCallId()).thenReturn("tc1");
        when(delta2.getDelta()).thenReturn("第二段");
        eventAdapter.handleEvent("session-1", delta2);

        // when
        List<DialogueMessage> snapshot = eventAdapter.getContext("session-1").getPersistenceSnapshot();

        // then
        // TOOL_CALL 入参已由 TOOL_CALL_END 收敛；结果累积到独立的 TOOL_RESULT 消息并从收集器注入
        assertEquals("{\"sql\":\"SELECT 1\"}", snapshot.get(1).getContent().input());
        assertEquals("", snapshot.get(1).getContent().result());
        DialogueMessage resultSnapshot = snapshot.get(snapshot.size() - 1);
        assertEquals(MessageType.TOOL_RESULT, resultSnapshot.getMessageType());
        assertEquals("generate_sql", resultSnapshot.getContent().title());
        assertEquals("第一段第二段", resultSnapshot.getContent().result());
    }
}
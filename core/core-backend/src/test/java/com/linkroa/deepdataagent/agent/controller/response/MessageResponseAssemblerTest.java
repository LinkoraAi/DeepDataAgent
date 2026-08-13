package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.DialogueContent;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageResponseAssembler 单元测试
 * <p>验证 DialogueMessage 领域模型到 MessageResponse DTO 的转换逻辑。</p>
 */
class MessageResponseAssemblerTest {

    private static final String SESSION_ID = "session-123";
    private static final Long DIALOGUE_ID = 100L;

    @Test
    void should_returnNull_when_toResponse_given_null() {
        // when
        MessageResponse result = MessageResponseAssembler.toResponse(null, SESSION_ID, DIALOGUE_ID);

        // then
        assertNull(result);
    }

    @Test
    void should_mapUserMessage_when_toResponse_given_userMessage() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                1L,
                MessageRole.USER,
                MessageType.MESSAGE,
                DialogueContent.text("用户问题"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("user", result.role());
        assertEquals("MESSAGE", result.type());
        assertEquals("用户问题", result.content());
        assertEquals(SESSION_ID, result.sessionId());
        assertNull(result.toolCalls());
        assertNull(result.toolResult());
        assertNull(result.toolCallId());
        assertNotNull(result.createdAt());
    }

    @Test
    void should_mapAssistantMessage_when_toResponse_given_assistantMessage() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                2L,
                MessageRole.ASSISTANT,
                MessageType.MESSAGE,
                DialogueContent.text("助手回复"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(2L, result.id());
        assertEquals("assistant", result.role());
        assertEquals("MESSAGE", result.type());
        assertEquals("", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_mapToolCallMessage_when_toResponse_given_toolCall() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                3L,
                MessageRole.ASSISTANT,
                MessageType.TOOL_CALL,
                DialogueContent.toolCall("query_database", "{\"sql\": \"SELECT * FROM users\"}", "", "call-1"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(3L, result.id());
        assertEquals("TOOL_CALL", result.type());
        assertEquals("query_database", result.toolCalls());
        assertEquals("{\"sql\": \"SELECT * FROM users\"}", result.content());
        assertNull(result.toolResult());
        assertEquals("call-1", result.toolCallId());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_mapToolCallWithoutResult_when_toResponse_given_toolCallMessage() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                3L,
                MessageRole.ASSISTANT,
                MessageType.TOOL_CALL,
                DialogueContent.toolCall("query_database", "{\"sql\": \"SELECT * FROM users\"}", "查询到的数据", "call-2"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        // TOOL_CALL 消息仅透出工具名与入参，结果由独立的 TOOL_RESULT 消息承载
        assertEquals(3L, result.id());
        assertEquals("TOOL_CALL", result.type());
        assertEquals("query_database", result.toolCalls());
        assertEquals("{\"sql\": \"SELECT * FROM users\"}", result.content());
        assertNull(result.toolResult());
        assertEquals("call-2", result.toolCallId());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_mapToolResultMessage_when_toResponse_given_toolResult() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                4L,
                MessageRole.TOOL,
                MessageType.TOOL_RESULT,
                DialogueContent.toolResult("query_database", "查询结果数据", "call-1"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        // TOOL_RESULT 同时透出工具名（toolCalls）与返回结果（toolResult），并与对应调用共享 toolCallId
        assertEquals(4L, result.id());
        assertEquals("TOOL_RESULT", result.type());
        assertEquals("tool", result.role());
        assertEquals("query_database", result.toolCalls());
        assertEquals("查询结果数据", result.toolResult());
        assertEquals("call-1", result.toolCallId());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_mapThinkingMessage_when_toResponse_given_thinking() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                5L,
                MessageRole.ASSISTANT,
                MessageType.THINKING,
                DialogueContent.text("正在思考..."),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(5L, result.id());
        assertEquals("thinking", result.role());
        assertEquals("THINKING", result.type());
        assertEquals("正在思考...", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullContent_when_toResponse_given_nullContent() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                6L,
                MessageRole.USER,
                MessageType.MESSAGE,
                null,
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(6L, result.id());
        assertEquals("user", result.role());
        assertEquals("", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullSequenceNumber_when_toResponse_given_nullSequenceNumber() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                null,
                MessageRole.USER,
                MessageType.MESSAGE,
                DialogueContent.text("测试"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertNull(result.id());
        assertEquals("user", result.role());
        assertEquals("测试", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullType_when_toResponse_given_contentWithNullType() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                7L,
                MessageRole.USER,
                null,
                DialogueContent.text("测试内容"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(7L, result.id());
        assertEquals("user", result.role());
        assertNull(result.type());
        assertEquals("", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullRole_when_toResponse_given_nullRole() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                8L,
                null,
                MessageType.MESSAGE,
                DialogueContent.text("测试"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(8L, result.id());
        assertNull(result.role());
        assertEquals("MESSAGE", result.type());
        assertEquals("测试", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleUnknownType_when_toResponse_given_unknownMessageType() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                9L,
                MessageRole.USER,
                MessageType.ERROR,
                DialogueContent.text("未知类型"),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(9L, result.id());
        assertEquals("user", result.role());
        assertEquals("未知类型", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullToolCallInput_when_toResponse_given_toolCallWithNullInput() {
        // given
        LocalDateTime now = LocalDateTime.now();
        DialogueMessage message = new DialogueMessage(
                10L,
                MessageRole.ASSISTANT,
                MessageType.TOOL_CALL,
                new DialogueContent("query_database", null, null, null),
                MessageStatus.COMPLETED,
                now,
                now
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(10L, result.id());
        assertEquals("query_database", result.toolCalls());
        assertEquals("", result.content());
        assertEquals(SESSION_ID, result.sessionId());
    }

    @Test
    void should_handleNullStartTime_when_toResponse_given_nullStartTime() {
        // given
        DialogueMessage message = new DialogueMessage(
                11L,
                MessageRole.USER,
                MessageType.MESSAGE,
                DialogueContent.text("测试"),
                MessageStatus.COMPLETED,
                null,
                null
        );

        // when
        MessageResponse result = MessageResponseAssembler.toResponse(message, SESSION_ID, DIALOGUE_ID);

        // then
        assertEquals(11L, result.id());
        assertEquals("user", result.role());
        assertEquals("测试", result.content());
        assertNull(result.createdAt());
        assertEquals(SESSION_ID, result.sessionId());
    }
}
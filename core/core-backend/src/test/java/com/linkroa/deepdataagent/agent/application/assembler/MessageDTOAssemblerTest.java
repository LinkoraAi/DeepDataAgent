package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.domain.model.DialogueContent;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MessageDTOAssembler} 单元测试
 * <p>覆盖 toDTO 方法的正常转换、各 MessageType 分支、各 MessageRole 分支、null 处理等场景。</p>
 */
class MessageDTOAssemblerTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Long DIALOGUE_ID = 100L;

    /**
     * 构造指定字段的 DialogueMessage
     */
    private DialogueMessage buildMessage(MessageRole role, MessageType type, DialogueContent content,
                                         LocalDateTime startTime, Long sequenceNumber) {
        DialogueMessage message = new DialogueMessage();
        message.setRole(role);
        message.setMessageType(type);
        message.setContent(content);
        message.setStartTime(startTime);
        message.setSequenceNumber(sequenceNumber);
        return message;
    }

    /**
     * 覆盖：message 为 null 时返回 null
     */
    @Test
    public void should_returnNull_when_toDTO_given_nullMessage() {
        // given
        DialogueMessage message = null;

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertNull(result);
    }

    /**
     * 覆盖：TOOL_CALL 类型且 input 不为 null
     */
    @Test
    public void should_mapToolCallFields_when_toDTO_given_toolCallWithInput() {
        // given
        LocalDateTime startTime = LocalDateTime.of(2026, 8, 3, 10, 30, 0);
        DialogueContent content = DialogueContent.toolCall("sql_executor", "{\"sql\":\"select 1\"}", "result");
        DialogueMessage message = buildMessage(MessageRole.TOOL, MessageType.TOOL_CALL, content, startTime, 1L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals(1L, result.id());
        assertEquals("session-1", result.sessionId());
        assertEquals("tool", result.role());
        assertEquals("{\"sql\":\"select 1\"}", result.content());
        assertEquals("sql_executor", result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：TOOL_CALL 类型且 input 为 null 时 content 为空串
     */
    @Test
    public void should_mapToolCallWithEmptyContent_when_toDTO_given_toolCallWithoutInput() {
        // given
        DialogueContent content = new DialogueContent("sql_executor", null, "result");
        DialogueMessage message = buildMessage(MessageRole.TOOL, MessageType.TOOL_CALL, content,
                LocalDateTime.now(), 1L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("", result.content());
        assertEquals("sql_executor", result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：TOOL_RESULT 类型映射 toolResult 字段
     */
    @Test
    public void should_mapToolResult_when_toDTO_given_toolResultType() {
        // given
        DialogueContent content = DialogueContent.toolResult("{\"rows\":1}");
        DialogueMessage message = buildMessage(MessageRole.TOOL, MessageType.TOOL_RESULT, content,
                LocalDateTime.now(), 2L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("{\"rows\":1}", result.toolResult());
        assertNull(result.toolCalls());
        assertEquals("", result.content());
    }

    /**
     * 覆盖：THINKING 类型映射 result 为 content
     */
    @Test
    public void should_mapThinkingText_when_toDTO_given_thinkingType() {
        // given
        DialogueContent content = DialogueContent.text("正在思考...");
        DialogueMessage message = buildMessage(MessageRole.THINKING, MessageType.THINKING, content,
                LocalDateTime.now(), 3L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("正在思考...", result.content());
        assertNull(result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：MESSAGE 类型且 ASSISTANT 角色时 result 映射为 content（分析报告）
     */
    @Test
    public void should_mapAssistantMessage_when_toDTO_given_assistantMessage() {
        // given
        DialogueContent content = DialogueContent.text("分析结果");
        DialogueMessage message = buildMessage(MessageRole.ASSISTANT, MessageType.MESSAGE, content,
                LocalDateTime.now(), 4L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("assistant", result.role());
        assertEquals("分析结果", result.content());
        assertNull(result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：MESSAGE 类型且非 ASSISTANT 角色时 result 映射为 content
     */
    @Test
    public void should_mapUserText_when_toDTO_given_userMessage() {
        // given
        DialogueContent content = DialogueContent.text("请帮我分析");
        DialogueMessage message = buildMessage(MessageRole.USER, MessageType.MESSAGE, content,
                LocalDateTime.now(), 5L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("user", result.role());
        assertEquals("请帮我分析", result.content());
    }

    /**
     * 覆盖：默认分支（ERROR 类型）result 映射为 content
     */
    @Test
    public void should_mapErrorText_when_toDTO_given_errorType() {
        // given
        DialogueContent content = DialogueContent.text("查询失败");
        DialogueMessage message = buildMessage(MessageRole.SYSTEM, MessageType.ERROR, content,
                LocalDateTime.now(), 6L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("system", result.role());
        assertEquals("查询失败", result.content());
    }

    /**
     * 覆盖：content 为 null 时各字段保持默认（不进入 switch）
     */
    @Test
    public void should_returnDefaultFields_when_toDTO_given_nullContent() {
        // given
        DialogueMessage message = buildMessage(MessageRole.USER, MessageType.MESSAGE, null,
                LocalDateTime.now(), 7L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("", result.content());
        assertNull(result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：type 为 null 时各字段保持默认（不进入 switch）
     */
    @Test
    public void should_returnDefaultFields_when_toDTO_given_nullType() {
        // given
        DialogueContent content = DialogueContent.text("文本");
        DialogueMessage message = buildMessage(MessageRole.USER, null, content, LocalDateTime.now(), 8L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("", result.content());
        assertNull(result.toolCalls());
        assertNull(result.toolResult());
    }

    /**
     * 覆盖：role 为 null 时 role 字符串为 null
     */
    @Test
    public void should_returnNullRole_when_toDTO_given_nullRole() {
        // given
        DialogueContent content = DialogueContent.text("文本");
        DialogueMessage message = buildMessage(null, MessageType.MESSAGE, content, LocalDateTime.now(), 9L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertNull(result.role());
        assertEquals("文本", result.content());
    }

    /**
     * 覆盖：startTime 为 null 时 createdAt 为 null
     */
    @Test
    public void should_returnNullCreatedAt_when_toDTO_given_nullStartTime() {
        // given
        DialogueContent content = DialogueContent.text("文本");
        DialogueMessage message = buildMessage(MessageRole.USER, MessageType.MESSAGE, content, null, 10L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertNull(result.createdAt());
    }

    /**
     * 覆盖：startTime 非 null 时按指定格式格式化 createdAt
     */
    @Test
    public void should_formatCreatedAt_when_toDTO_given_startTime() {
        // given
        LocalDateTime startTime = LocalDateTime.of(2026, 8, 3, 10, 30, 45);
        DialogueContent content = DialogueContent.text("文本");
        DialogueMessage message = buildMessage(MessageRole.USER, MessageType.MESSAGE, content, startTime, 11L);

        // when
        MessageDTO result = MessageDTOAssembler.toDTO(message, "session-1", DIALOGUE_ID);

        // then
        assertEquals("2026-08-03 10:30:45", result.createdAt());
        assertEquals(startTime.format(FORMATTER), result.createdAt());
    }
}